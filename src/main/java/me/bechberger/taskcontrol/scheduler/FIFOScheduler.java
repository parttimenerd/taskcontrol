package me.bechberger.taskcontrol.scheduler;

import me.bechberger.ebpf.annotations.AlwaysInline;
import me.bechberger.ebpf.annotations.Unsigned;
import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.annotations.bpf.BPFFunction;
import me.bechberger.ebpf.annotations.bpf.BPFMapDefinition;
import me.bechberger.ebpf.annotations.bpf.Property;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.map.BPFHashMap;
import me.bechberger.ebpf.runtime.BpfDefinitions;
import me.bechberger.ebpf.runtime.TaskDefinitions;
import me.bechberger.ebpf.type.Ptr;

import static me.bechberger.ebpf.runtime.BpfDefinitions.bpf_cpumask_test_cpu;
import static me.bechberger.ebpf.runtime.ScxDefinitions.*;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_bpf_dispatch_from_dsq;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_dsq_id_flags.SCX_DSQ_LOCAL_ON;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_enq_flags.SCX_ENQ_PREEMPT;

/**
 * FIFO scheduler that allows stopping tasks
 */
@BPF(license = "GPL")
@Property(name = "sched_name", value = "minimal_stopping_scheduler")
public abstract class FIFOScheduler extends BPFProgram implements BaseScheduler {

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
        scx_bpf_dispatch(p, SHARED_DSQ_ID, sliceLength, enq_flags);
    }

    @BPFFunction
    @AlwaysInline
    public boolean shouldStop(Ptr<TaskDefinitions.task_struct> p) {
        var taskSetting = taskSettings.bpf_get(p.val().pid);
        var groupSetting = taskGroupSettings.bpf_get(p.val().tgid);
        return (taskSetting != null && taskSetting.val().stop()) || (groupSetting != null && groupSetting.val().stop());
    }

    @BPFFunction
    @AlwaysInline
    public boolean tryDispatching(Ptr<BpfDefinitions.bpf_iter_scx_dsq> iter, Ptr<TaskDefinitions.task_struct> p, int cpu) {
        if (shouldStop(p)) {
            return false;
        }
        // check if the CPU is usable by the task
        if (!bpf_cpumask_test_cpu(cpu, p.val().cpus_ptr)) {
            return false;
        }
        return scx_bpf_dispatch_from_dsq(iter, p, SCX_DSQ_LOCAL_ON.value() | cpu, SCX_ENQ_PREEMPT.value());
    }

    @Override
    public void dispatch(int cpu, Ptr<TaskDefinitions.task_struct> prev) {
        // macros like bpf_for_each are not yet supported in hello-ebpf Java
        String CODE = """
                    s32 this_cpu = bpf_get_smp_processor_id();
                    struct task_struct *p;
                    bpf_for_each(scx_dsq, p, SHARED_DSQ_ID, 0) {
                       if (tryDispatching(BPF_FOR_EACH_ITER, p, this_cpu)) {
                           break;
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