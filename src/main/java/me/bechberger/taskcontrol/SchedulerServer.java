package me.bechberger.taskcontrol;

import io.javalin.Javalin;
import io.javalin.http.Context;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.map.BPFHashMap;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.bechberger.taskcontrol.scheduler.BaseScheduler;
import me.bechberger.taskcontrol.scheduler.FIFOScheduler;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
/**
 * A FIFO scheduler with a rest API to stop tasks
 */
@Command(name = "scheduler", mixinStandardHelpOptions = true, version = "scheduler 1.0",
        description = "A FIFO scheduler with a rest API to stop tasks")
public class SchedulerServer implements Callable<Integer> {

    public static final int DEFAULT_PORT = 8087;

    private static final String SERVER_HELP = """
            GET localhost:PORT/task/{id} to get the status of a task
            GET localhost:PORT/task/{id}?stopping=true|false to stop or resume a task
            GET localhost:PORT/task/plan/{id}?plan=s10,r10 to set the plan for a task (e.g. 10s running, 10s stopped)
            GET localhost:PORT/task/plan/{id} to get the current plan for a task
            GET localhost:PORT/plans the current plans as JSON

            The same for taskGroup (process)
            """;

    static class PlanInvalidException extends Exception {
        public PlanInvalidException(String message) {
            super(message);
        }
    }

    record SchedulePlanItem(Duration duration, boolean stopping) {
        public static List<SchedulePlanItem> parsePlan(String plan) throws PlanInvalidException {
            if (!plan.matches("(\\d+\\.?\\d*[sr],)*\\d+\\.?\\d*[sr]")) {
                throw new PlanInvalidException("Invalid plan, must be a comma separated list of items like s10,r10");
            }
            var items = Stream.of(plan.split(","))
                    .filter(part -> !part.isEmpty())
                    .map(part -> {
                        boolean stopping = part.endsWith("s");
                        float duration = Float.parseFloat(part.substring(0, part.length() - 1));
                        return new SchedulePlanItem(Duration.ofNanos(Math.round(duration * 1.0 * 1_000_000_000L)), stopping);
                    })
                    .toList();
            var itemsInvalid = items.stream().anyMatch(item ->
                    item.duration.isNegative() || item.duration.isZero() || (item.stopping && item.duration.getSeconds() > 25));
            if (itemsInvalid) {
                throw new PlanInvalidException("Invalid plan, duration must be positive and stopping durations less than 25s");
            }
            return items;
        }

        @Override
        public String toString() {
            var prefix = stopping ? "r" : "s";
            if (duration.toNanosPart() == 0) {
                return prefix + duration.getSeconds();
            }
            return duration.getSeconds() + "." + duration.toNanosPart() / 1_000_000_000 + prefix;
        }
    }

    static class SchedulePlanRunner extends Thread {

        private long startedAt = 0;

        private final BPFHashMap<Integer, BaseScheduler.TaskSetting> settingsMap;

        private final int id;

        private final List<SchedulePlanItem> plan;

        private final Runnable removeRunner;

        private final AtomicBoolean running = new AtomicBoolean(true);

        public SchedulePlanRunner(BPFHashMap<Integer, BaseScheduler.TaskSetting> settingsMap, int id,
                                  List<SchedulePlanItem> plan, Runnable removeRunner) {
            this.settingsMap = settingsMap;
            this.id = id;
            this.plan = plan;
            this.removeRunner = removeRunner;
        }

        @Override
        public void run() {
            startedAt = System.currentTimeMillis();
            System.out.println("Starting plan " + id + ": " + currentPlan());
            for (SchedulePlanItem item : plan) {
                if (!running.get()) {
                    break;
                }
                settingsMap.put(id, new BaseScheduler.TaskSetting(item.stopping()));
                try {
                    Thread.sleep(item.duration);
                } catch (InterruptedException e) {
                    settingsMap.put(id, new BaseScheduler.TaskSetting(false));
                    throw new RuntimeException(e);
                }
            }
            settingsMap.put(id, new BaseScheduler.TaskSetting(false));
            removeRunner.run();
        }

        public void stopRunning() {
            running.set(false);
        }

        public String currentPlan() {
            return plan.stream()
                    .map(Record::toString)
                    .collect(Collectors.joining(","));
        }
        public long getStartedAt() {
            return startedAt;
        }
    }

    static class SchedulePlanManager {
        private final BPFHashMap<Integer, BaseScheduler.TaskSetting> settingsMap;
        private final Map<Integer, SchedulePlanRunner> runners;

        public SchedulePlanManager(BPFHashMap<Integer, BaseScheduler.TaskSetting> settingsMap) {
            this.settingsMap = settingsMap;
            this.runners = new HashMap<>();
        }

        public void setPlan(int id, String plan) throws PlanInvalidException {
            var items = SchedulePlanItem.parsePlan(plan);
            var runner = new SchedulePlanRunner(settingsMap, id, items, () -> {
                synchronized (this){
                    runners.remove(id);
                }
            });
            SchedulePlanRunner oldRunner;
            synchronized (this) {
                oldRunner = runners.put(id, runner);
            }
            if (oldRunner != null) {
                oldRunner.stopRunning();
            }
            runner.start();
        }

        public void stopPlan(int id) {
            synchronized (this) {
                var runner = runners.get(id);
                if (runner != null) {
                    runner.stopRunning();
                }
            }
        }

        public synchronized String getCurrentPlan(int id) {
            return Optional.ofNullable(runners.get(id))
                    .map(SchedulePlanRunner::currentPlan)
                    .orElse("no plan");
        }

        record CurrentPlan(int id, String plan, long startedAt) {}

        public synchronized Map<Integer, CurrentPlan> getCurrentPlans() {
            return runners.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                        var runner = entry.getValue();
                        return new CurrentPlan(entry.getKey(), runner.currentPlan(), runner.getStartedAt());
                    }));
        }
    }

    public void launchServer(BaseScheduler scheduler, int port) {
        Javalin app = Javalin.create().start(port);

        SchedulePlanManager taskPlanManager = new SchedulePlanManager(scheduler.getTaskSettingsMap());
        SchedulePlanManager taskGroupPlanManager = new SchedulePlanManager(scheduler.getTaskGroupSettingsMap());

        app.get("/help", ctx -> {
            String response = SERVER_HELP.replace("PORT", port + "");
            ctx.result(response);
        });

        BiConsumer<BPFHashMap<Integer, BaseScheduler.TaskSetting>, Context> handleSettings = (map, ctx) -> {
            String idParam = ctx.pathParam("id");
            int id;
            try {
                id = Integer.parseInt(idParam);
            } catch (NumberFormatException e) {
                ctx.status(400).result("Bad Request");
                return;
            }

            String stopping = ctx.queryParam("stopping");

            String response;
            if (stopping == null) {
                response = Optional.ofNullable(map.get(id))
                        .map(setting -> setting.stop() ? "stopping" : "running")
                        .orElse("not found");
            } else {
                map.put(id, new BaseScheduler.TaskSetting(Boolean.parseBoolean(stopping)));
                response = "ok";
            }

            ctx.result(response);
        };

        app.get("/task/{id}", ctx -> handleSettings.accept(scheduler.getTaskSettingsMap(), ctx));
        app.get("/taskGroup/{id}", ctx -> handleSettings.accept(scheduler.getTaskGroupSettingsMap(), ctx));

        BiConsumer<SchedulePlanManager, Context> handlePlan = (manager, ctx) -> {
            String idParam = ctx.pathParam("id");
            int id;
            try {
                id = Integer.parseInt(idParam);
            } catch (NumberFormatException e) {
                ctx.status(400).result("Bad Request");
                return;
            }

            String plan = ctx.queryParam("plan");
            String stoppingPlan = ctx.queryParam("stoppingPlan");

            if (plan != null && stoppingPlan != null) {
                ctx.status(400).result("Bad Request, only one of plan or stoppingPlan can be set");
                return;
            }

            if (plan == null) {
                if (stoppingPlan != null) {
                    if (Boolean.parseBoolean(stoppingPlan)) {
                        manager.stopPlan(id);
                        ctx.result("ok");
                    }
                    return;
                }
                String response = manager.getCurrentPlan(id);
                ctx.result(response);
            } else {
                try {
                    manager.setPlan(id, plan);
                    ctx.result("ok");
                } catch (PlanInvalidException e) {
                    ctx.status(400).result(e.getMessage());
                }
            }
        };

        app.get("/task/plan/{id}", ctx -> handlePlan.accept(taskPlanManager, ctx));

        app.get("/taskGroup/plan/{id}", ctx -> handlePlan.accept(taskGroupPlanManager, ctx));

        app.get("/plans", ctx -> {
            // print all active plans
            var taskPlans = taskPlanManager.getCurrentPlans();
            var taskGroupPlans = taskGroupPlanManager.getCurrentPlans();
            ctx.json(Map.of("task", taskPlans, "taskGroup", taskGroupPlans));
        });

        System.out.println("Starting server on port " + port);
        System.out.println(SERVER_HELP.replace("PORT", port + ""));
    }

    @Option(names = {"-p", "--port"}, description = "The port to listen on", defaultValue = "" + DEFAULT_PORT)
    private int port;

    enum SchedulerType {
        fifo(FIFOScheduler.class);
        private final Class<? extends BaseScheduler> schedulerClass;
        SchedulerType(Class<? extends BaseScheduler> schedulerClass) {
            this.schedulerClass = schedulerClass;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public BaseScheduler load() {
            return BPFProgram.load((Class<BPFProgram>)(Class)schedulerClass);
        }
    }

    @Option(names = {"-s", "--scheduler"}, description = "The scheduler to use, available schedulers: ${COMPLETION-CANDIDATES}", defaultValue = "fifo")
    private SchedulerType schedulerType;

    @Override
    public Integer call() throws Exception {
        try (var program = schedulerType.load()) {
            program.attachScheduler();
            new Thread(program::tracePrintLoop).start();
            launchServer(program, port);
            while (program.isSchedulerAttachedProperly()) {
                Thread.sleep(1000);
            }
        }
        return 0;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = new CommandLine(new SchedulerServer()).execute(args);
        System.exit(exitCode);
    }
}
