package me.bechberger.taskcontrol.scheduler;

import me.bechberger.ebpf.annotations.Type;
import me.bechberger.ebpf.annotations.bpf.BPFInterface;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.Scheduler;
import me.bechberger.ebpf.bpf.map.BPFHashMap;

@BPFInterface
public interface BaseScheduler extends Scheduler, AutoCloseable {

    @Type
    record TaskSetting(boolean stop) {
    }

    default void tracePrintLoop() {
        if (this instanceof BPFProgram program) {
            program.tracePrintLoop();
        }
    }

    BPFHashMap<Integer, TaskSetting> getTaskSettingsMap();
    BPFHashMap<Integer, TaskSetting> getTaskGroupSettingsMap();
}
