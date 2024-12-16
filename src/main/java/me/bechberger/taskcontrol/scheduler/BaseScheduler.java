package me.bechberger.taskcontrol.scheduler;

import me.bechberger.ebpf.annotations.Type;
import me.bechberger.ebpf.annotations.Unsigned;
import me.bechberger.ebpf.annotations.bpf.BPFInterface;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.Scheduler;
import me.bechberger.ebpf.bpf.map.BPFHashMap;

@BPFInterface
public interface BaseScheduler extends Scheduler, AutoCloseable {

    /**
     *
     * @param stop stop scheduling
     * @param lotteryPriority positive priority for the {@link LotteryScheduler}
     */
    @Type
    record TaskSetting(boolean stop, @Unsigned int lotteryPriority) {
        public TaskSetting {
            if (lotteryPriority <= 0) {
                throw new IllegalArgumentException("lotteryPriority has to be positive, got " + lotteryPriority);
            }
        }
    }

    default void tracePrintLoop() {
        if (this instanceof BPFProgram program) {
            program.tracePrintLoop();
        }
    }

    BPFHashMap<Integer, TaskSetting> getTaskSettingsMap();
    BPFHashMap<Integer, TaskSetting> getTaskGroupSettingsMap();
}
