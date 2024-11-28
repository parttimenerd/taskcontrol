package me.bechberger.taskcontrol.util;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;

/** Threads related to this process, caches {@link ExtendedThreadInfo#getAll()} */
public class ProcessThreads {

    private Map<Long, ExtendedThreadInfo> threads;

    public ProcessThreads() {
        this.threads = ExtendedThreadInfo.getAll();
    }

    public ExtendedThreadInfo get(Thread thread) {
        return this.getByJavaId(thread.threadId());
    }

    public ExtendedThreadInfo getByJavaId(long javaThreadId) {
        return this.threads.get(javaThreadId);
    }

    public @Nullable ExtendedThreadInfo getByName(String threadName) {
        return this.threads.values().stream()
                .filter(thread -> thread.threadName().equals(threadName))
                .findFirst()
                .orElse(null);
    }

    public Map<Long, ExtendedThreadInfo> getThreads() {
        return Collections.unmodifiableMap(this.threads);
    }

    public void update() {
        this.threads = ExtendedThreadInfo.getAll();
    }
}
