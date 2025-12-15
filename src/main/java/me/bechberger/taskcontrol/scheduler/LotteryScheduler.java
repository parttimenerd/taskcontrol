package me.bechberger.taskcontrol.scheduler;

import me.bechberger.ebpf.annotations.AlwaysInline;
import me.bechberger.ebpf.annotations.Unsigned;
import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.annotations.bpf.BPFFunction;
import me.bechberger.ebpf.annotations.bpf.BPFMapDefinition;
import me.bechberger.ebpf.annotations.bpf.Property;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.GlobalVariable;
import me.bechberger.ebpf.bpf.map.BPFHashMap;
import me.bechberger.ebpf.runtime.BpfDefinitions;
import me.bechberger.ebpf.runtime.TaskDefinitions;
import me.bechberger.ebpf.type.Ptr;

import static me.bechberger.ebpf.runtime.BpfDefinitions.bpf_cpumask_test_cpu;
import static me.bechberger.ebpf.runtime.ScxDefinitions.*;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_dsq_id_flags.SCX_DSQ_LOCAL_ON;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_enq_flags.SCX_ENQ_PREEMPT;

/**
 * Lottery scheduler that allows stopping tasks and assigning priorities (1 for lowest)
 */
@BPF(license = "GPL")
@Property(name = "sched_name", value = "lottery_stopping_scheduler")
public abstract class LotteryScheduler extends BPFProgram implements BaseScheduler {

    private static final int SHARED_DSQ_ID = 0;

    @BPFMapDefinition(maxEntries = 10000)
    BPFHashMap<Integer, TaskSetting> taskSettings;

    @BPFMapDefinition(maxEntries = 10000)
    BPFHashMap<Integer, TaskSetting> taskGroupSettings;

    @Override
    public int init() {
        return scx_bpf_create_dsq(SHARED_DSQ_ID, -1);
    }

    @Override
    public void enqueue(Ptr<TaskDefinitions.task_struct> p, long enq_flags) {
        var sliceLength = ((@Unsigned int) 5_000_000) / scx_bpf_dsq_nr_queued(SHARED_DSQ_ID);
        scx_bpf_dsq_insert(p, SHARED_DSQ_ID, sliceLength, enq_flags);
    }

    @BPFFunction
    @AlwaysInline
    public void getSetting(Ptr<TaskDefinitions.task_struct> p, Ptr<TaskSetting> out) {
        var taskSetting = taskSettings.bpf_get(p.val().pid);
        if (taskSetting != null) {
            out.set(taskSetting.val());
            return;
        }
        var groupSetting = taskGroupSettings.bpf_get(p.val().tgid);
        if (groupSetting != null) {
            out.set(groupSetting.val());
            return;
        }
        out.set(new TaskSetting(false, 1));
    }

    @BPFFunction
    @AlwaysInline
    public int getPriorityIfNotStopped(Ptr<TaskDefinitions.task_struct> p) {
        TaskSetting setting = new TaskSetting(false, 1);
        getSetting(p, Ptr.of(setting));
        if (setting.stop()) {
            return 0;
        }
        return setting.lotteryPriority();
    }

    @BPFFunction
    @AlwaysInline
    public boolean tryDispatching(Ptr<BpfDefinitions.bpf_iter_scx_dsq> iter, Ptr<TaskDefinitions.task_struct> p, int cpu) {
        // check if the CPU is usable by the task
        if (!bpf_cpumask_test_cpu(cpu, p.val().cpus_ptr)) {
            return false;
        }
        return scx_bpf_dsq_move(iter, p, SCX_DSQ_LOCAL_ON.value() | cpu, SCX_ENQ_PREEMPT.value());
    }

    /**
     * Dispatch tasks
     * <p/>
     *
     * iterate over all tasks in the shared DSQ to sum
     * the priorities of all tasks
     * pick random number between 0 and sum
     * iterate over all tasks in the shared DSQ and
     * subtract the priority of each task from the random number
     * if the random number is less than or equal to 0, dispatch the task
     */
    @Override
    public void dispatch(int cpu, Ptr<TaskDefinitions.task_struct> prev) {
        // macros like bpf_for_each are not yet supported in hello-ebpf Java
        String CODE = """
                    s32 this_cpu = bpf_get_smp_processor_id();
                    struct task_struct *p;
                    int sum = 0;
                    bpf_for_each(scx_dsq, p, SHARED_DSQ_ID, 0) {
                       sum += getPriorityIfNotStopped(p);
                    }
                    int random = bpf_get_prandom_u32() % sum;
                    bpf_for_each(scx_dsq, p, SHARED_DSQ_ID, 0) {
                        int prio = getPriorityIfNotStopped(p);
                        random -= prio;
                        if (random <= 0 && prio > 0 && tryDispatching(BPF_FOR_EACH_ITER, p, this_cpu)) {
                            return 0 ;
                        }
                    }
                """;
    }

    @Override
    public BPFHashMap<Integer, TaskSetting> getTaskGroupSettingsMap() {
        return taskGroupSettings;
    }

    @Override
    public BPFHashMap<Integer, TaskSetting> getTaskSettingsMap() {
        return taskSettings;
    }
}