package cn.classfun.droidvm.lib.utils;

import static java.util.concurrent.Executors.newCachedThreadPool;

import java.util.concurrent.Executor;

public final class ThreadUtils {
    public final static Executor pool = newCachedThreadPool();

    private ThreadUtils() {
    }

    public static void threadSleep(long millis, Runnable error) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            if (error != null) error.run();
        }
    }

    public static void threadSleep(long millis) {
        threadSleep(millis, null);
    }

    public static void threadSleepOrKill(long millis) {
        threadSleep(millis, () -> Thread.currentThread().interrupt());
    }

    public static void runOnPool(Runnable task) {
        pool.execute(task);
    }
}
