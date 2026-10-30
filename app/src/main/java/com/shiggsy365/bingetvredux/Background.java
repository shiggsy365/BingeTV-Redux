package com.shiggsy365.bingetvredux;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app's one pool of background threads, reused by every screen and helper instead of each
 * starting its own. Work goes through a {@link Tasks}, which runs a few at a time (one: in
 * order) and can cancel its own work without touching anyone else's.
 */
public final class Background {
    private static final AtomicInteger sCount = new AtomicInteger();
    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "bg-" + sCount.incrementAndGet());
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    };
    // Unbounded, so work waiting on other work (Parallel inside a screen's task) can't starve;
    // each Tasks keeps its own limit. Idle threads go after 30 seconds.
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
            30, TimeUnit.SECONDS, new SynchronousQueue<>(), FACTORY);

    private Background() {}

    /** One owner's work (a screen, a helper): at most {@code max} at a time, in order. */
    public static final class Tasks implements Executor {
        private final int mMax;
        private final ArrayDeque<Runnable> mQueue = new ArrayDeque<>();
        private final Set<Thread> mRunning = new HashSet<>();
        private final Set<Future<?>> mFutures = new HashSet<>();
        private boolean mShutdown;
        // Tasks handed to the pool that haven't registered their thread yet.
        private int starting;

        public Tasks(int max) {
            mMax = Math.max(1, max);
        }

        @Override
        public void execute(Runnable task) {
            synchronized (this) {
                if (mShutdown) return;
                mQueue.add(task);
            }
            drain();
        }

        /** Like {@link #execute}, with the result (or the task's exception) as a Future. */
        public <T> Future<T> submit(Callable<T> task) {
            FutureTask<T> future = new FutureTask<T>(task) {
                @Override
                protected void done() {
                    synchronized (Tasks.this) {
                        mFutures.remove(this);
                    }
                }
            };
            synchronized (this) {
                if (mShutdown) {
                    future.cancel(false);
                    return future;
                }
                mFutures.add(future);
            }
            execute(future);
            return future;
        }

        /** Drops work not started yet and interrupts what's running; nothing more is taken. */
        public void shutdownNow() {
            synchronized (this) {
                mShutdown = true;
                mQueue.clear();
                for (Future<?> f : mFutures) f.cancel(false);
                mFutures.clear();
                for (Thread t : mRunning) t.interrupt();
            }
        }

        private void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    if (mShutdown || mRunning.size() + starting >= mMax || mQueue.isEmpty()) return;
                    next = mQueue.poll();
                    starting++;
                }
                POOL.execute(() -> run(next));
            }
        }

        private void run(Runnable task) {
            Thread thread = Thread.currentThread();
            synchronized (this) {
                starting--;
                if (mShutdown) return;
                mRunning.add(thread);
            }
            try {
                task.run();
            } finally {
                synchronized (this) {
                    mRunning.remove(thread);
                }
                // A cancel that came as the task ended mustn't reach the thread's next owner.
                Thread.interrupted();
                drain();
            }
        }
    }
}
