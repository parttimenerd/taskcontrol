package me.bechberger.taskcontrol.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extended information on a Java thread
 * @param threadName name of the thread (see {@link Thread#getName()})
 * @param javaThreadId id of the thread in Java (see {@link Thread#threadId()})
 * @param osThreadId operating system thread id
 * @param osPrio priority on operating system level
 * @param userTime elapsed user CPU time (see {@link java.lang.management.ThreadMXBean#getThreadUserTime(long)}
 * @param cpuTime elapsed CPU time (see {@link java.lang.management.ThreadMXBean#getThreadCpuTime(long)}
 */
public record ExtendedThreadInfo(String threadName,
                                 long javaThreadId,
                                 long osThreadId,
                                 int osPrio,
                                 float userTime,
                                 float cpuTime) {

    /**
     * Get info on all Java threads of the current JVM
     * @return map of Java thread ids to their extended info
     * @throws RuntimeException if the jstack executable is not found or the process fails
     */
    public static Map<Long, ExtendedThreadInfo> getAll() {
        Map<Long, ExtendedThreadInfo> threadInfoMap = new HashMap<>();
        try {
            String pid = getCurrentJvmPid();

            Path jstackFile = getJStackExecutable().toAbsolutePath();
            if (!Files.exists(jstackFile)) {
                throw new RuntimeException("jstack not found at: " +
                        jstackFile);
            }

            // Execute jstack and collect thread information
            Process process = Runtime.getRuntime().exec(new String[]{
                    jstackFile.toString(), "-l", pid
            });
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            // Parse the jstack output and populate the thread info map
            String line;
            while ((line = reader.readLine()) != null) {
                ExtendedThreadInfo threadInfo = parseThreadInfo(line);
                if (threadInfo != null) {
                    threadInfoMap.put(threadInfo.javaThreadId(), threadInfo);
                }
            }

            // Wait for the jstack process to complete
            process.waitFor();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return threadInfoMap;
    }

    /**
     * Get os process id of the current JVM
     */
    public static String getCurrentJvmPid() {
        // Get the current JVM's process ID (PID)
        return ManagementFactory.getRuntimeMXBean().getName()
                .split("@")[0];
    }

    private static Path getJStackExecutable() {
        String javaHome = System.getProperty("java.home");
        return Path.of(javaHome).resolve("bin").resolve("jstack");
    }

    private static boolean isPossibleThreadInfoLine(String line) {
        return line.startsWith("\"") && line.contains("tid=") &&
                line.contains("nid=") && line.contains("os_prio=");
    }

    /** get all " property=value" pairs, but only after last '"' */
    private static Map<String, String> getPropertiesFromLine(String line) {
        String withoutString =
                line.substring(line.lastIndexOf('"') + 1);
        Map<String, String> properties = new HashMap<>();
        Pattern propertyPattern = Pattern.compile("(\\w+)=(\\S+)");
        Matcher matcher = propertyPattern.matcher(withoutString);
        while (matcher.find()) {
            properties.put(matcher.group(1), matcher.group(2));
        }
        return properties;
    }

    private static String getThreadNameFromLine(String line) {
        return line.substring(1, line.lastIndexOf("\""))
                .replaceAll("\"\"", "\"");
    }

    private static float parseTime(String time) {
        // could be "0.0ms" or "0.0s"
        if (time.endsWith("ms")) {
            return Float.parseFloat(time.substring(0, time.length() - 2)) / 1000;
        } else if (time.endsWith("s")) {
            return Float.parseFloat(time.substring(0, time.length() - 1));
        } else {
            throw new IllegalArgumentException("Invalid time format: " + time);
        }
    }

    /** Returns the Java thread id (the '#' after thread name) or -1 if not present */
    private static int getJavaThreadIdIfPresent(String line) {
        String afterThreadName = line.substring(line.lastIndexOf('"') + 2);
        if (afterThreadName.startsWith("#")) {
            return Integer.parseInt(
                    afterThreadName.substring(1, afterThreadName.indexOf(' ')));
        } else {
            return -1;
        }
    }

    private static ExtendedThreadInfo parseThreadInfo(String line) {
        if (!isPossibleThreadInfoLine(line)) {
            return null;
        }
        var threadName = getThreadNameFromLine(line);
        var properties = getPropertiesFromLine(line);

        long javaThreadId = getJavaThreadIdIfPresent(line);
        long osThreadId = Long.decode(properties.get("nid"));
        int osPrio = Integer.parseInt(properties.get("os_prio"));
        float cpuTime = parseTime(properties.get("cpu"));
        float userTime = parseTime(properties.get("elapsed"));

        return new ExtendedThreadInfo(threadName, javaThreadId,
                osThreadId, osPrio, userTime, cpuTime);
    }

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        for (ExtendedThreadInfo threadInfo : ExtendedThreadInfo.getAll().values()) {
            System.out.println(threadInfo);
        }
        System.out.println("took " + (System.currentTimeMillis() - start) + "ms");
    }
}
