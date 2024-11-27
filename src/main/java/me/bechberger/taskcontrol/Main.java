package me.bechberger.taskcontrol;

import me.bechberger.taskcontrol.util.ExtendedThreadInfo;

public class Main {

    static class ClockThread extends Thread {

        ClockThread() {
            super("ClockThread");
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(1000);
                    System.out.println("Tick");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public static void main(String[] args) throws InterruptedException {
        Thread clockThread = new ClockThread();
        clockThread.start();
        ThreadControl threadControl = new ThreadControl();
        ExtendedThreadInfo.getAll().forEach((id, info) -> {
            System.out.println("Thread " + id + ": " + info.threadName() + " " + info.osThreadId());
        });
        System.out.println("Looked at os id: " + threadControl.osId(clockThread));
        System.out.println("ClockThread status: " + threadControl.getThreadStatus(clockThread));
        Thread.sleep(100);
        threadControl.stopThread(clockThread);
        System.out.println("ClockThread status: " + threadControl.getThreadStatus(clockThread));
        Thread.sleep(10000);
        threadControl.resumeThread(clockThread);
    }
}