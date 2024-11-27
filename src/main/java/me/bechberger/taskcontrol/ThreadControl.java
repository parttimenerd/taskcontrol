package me.bechberger.taskcontrol;

import me.bechberger.taskcontrol.util.ExtendedThreadInfo;
import me.bechberger.taskcontrol.util.ProcessThreads;

public class ThreadControl {

    private final ProcessThreads processThreads = new ProcessThreads();
    private final RestSchedulerClient schedulerClient;

    /** Start the scheduler with the default port from {@code ./scheduler.sh} */
    public ThreadControl() {
        this(8087);
    }

    public ThreadControl(int port) {
        this.schedulerClient = new RestSchedulerClient(SchedulerServer.DEFAULT_PORT);
    }

    public long osId(Thread thread) {
        ExtendedThreadInfo info = this.processThreads.get(thread);
        if (info == null) {
            processThreads.update();
        }
        return this.processThreads.get(thread).osThreadId();
    }

    public RestSchedulerClient.TaskStatus getThreadStatus(Thread thread) {
        return this.schedulerClient.getTaskStatus(osId(thread));
    }

    public void stopThread(Thread thread) {
        this.schedulerClient.stop(osId(thread));
    }

    public void resumeThread(Thread thread) {
        this.schedulerClient.resume(osId(thread));
    }
}
