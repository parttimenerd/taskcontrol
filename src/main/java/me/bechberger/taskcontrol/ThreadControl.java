package me.bechberger.taskcontrol;

import me.bechberger.taskcontrol.util.ExtendedThreadInfo;
import me.bechberger.taskcontrol.util.ProcessThreads;

import static me.bechberger.taskcontrol.SchedulerServer.DEFAULT_PORT;

/** Control the scheduling of Java threads */
public class ThreadControl {

    private final ProcessThreads processThreads = new ProcessThreads();
    private final RestSchedulerClient schedulerClient;

    /** Start the scheduler with the default port from {@code ./scheduler.sh} */
    public ThreadControl() {
        this(DEFAULT_PORT);
    }

    public ThreadControl(int port) {
        this.schedulerClient = new RestSchedulerClient(port);
    }

    /** Map the Java thread to the OS thread ID */
    public long osId(Thread thread) {
        ExtendedThreadInfo info = this.processThreads.get(thread);
        if (info == null) {
            processThreads.update();
        }
        return this.processThreads.get(thread).osThreadId();
    }

    /** Get the scheduling status for a given thread */
    public RestSchedulerClient.TaskStatus getThreadStatus(Thread thread) {
        return this.schedulerClient.getTaskStatus(osId(thread));
    }

    /** Prevent a thread from being rescheduled */
    public void stopThread(Thread thread) {
        this.schedulerClient.stop(osId(thread));
    }

    /** Allow a thread to be rescheduled */
    public void resumeThread(Thread thread) {
        this.schedulerClient.resume(osId(thread));
    }
}
