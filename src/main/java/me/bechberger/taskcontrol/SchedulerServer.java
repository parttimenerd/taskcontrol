// SPDX-License-Identifier: GPL-2.0

package me.bechberger.taskcontrol;

import io.javalin.Javalin;
import io.javalin.http.Context;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.map.BPFHashMap;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

import me.bechberger.taskcontrol.scheduler.BaseScheduler;
import me.bechberger.taskcontrol.scheduler.FIFOScheduler;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
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
            GET localhost:PORT/taskGroup/{id} to get the status of a task group (i.e. process)
            GET localhost:PORT/taskGroup/{id}?stopping=true|false to stop or resume a task group
            """;

    public void launchServer(BaseScheduler scheduler, int port) throws IOException {
        Javalin app = Javalin.create().start(port);

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

        app.get("/task/{id}", ctx -> {
            handleSettings.accept(scheduler.getTaskSettingsMap(), ctx);
        });
        app.get("/taskGroup/{id}", ctx -> {
            handleSettings.accept(scheduler.getTaskGroupSettingsMap(), ctx);
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
            new Thread(() -> {
                program.tracePrintLoop();
            }).start();
            launchServer(program, port);
            Thread.sleep(1000000000);
        }
        return 0;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = new CommandLine(new SchedulerServer()).execute(args);
        System.exit(exitCode);
    }
}
