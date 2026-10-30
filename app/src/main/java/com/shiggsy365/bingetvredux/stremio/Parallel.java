package com.shiggsy365.bingetvredux.stremio;

import com.shiggsy365.bingetvredux.Background;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Asks several addons at once. */
public final class Parallel {
    private static final int MAX_THREADS = 8;

    private Parallel() {}

    /** One task's outcome: its value, or why it failed. */
    public static final class Result<T> {
        public final T value;
        public final Exception error;

        Result(T value, Exception error) {
            this.value = value;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** Hears each task's result as it finishes, one at a time, on the task's thread. */
    public interface Each<T> {
        void done(int index, Result<T> result);
    }

    /**
     * Runs the tasks together and returns their results in task order. Tasks still running
     * after {@code timeoutMs} count as failed.
     */
    public static <T> List<Result<T>> run(List<Callable<T>> tasks, long timeoutMs) {
        return run(tasks, timeoutMs, null);
    }

    /** {@link #run(List, long)}, telling {@code each} about every result as it comes. */
    public static <T> List<Result<T>> run(List<Callable<T>> tasks, long timeoutMs, Each<T> each) {
        List<Result<T>> results = new ArrayList<>();
        if (tasks.isEmpty()) return results;
        if (tasks.size() == 1) {
            Result<T> result;
            try {
                result = new Result<>(tasks.get(0).call(), null);
            } catch (Exception e) {
                result = new Result<>(null, e);
            }
            if (each != null) each.done(0, result);
            results.add(result);
            return results;
        }
        if (each != null) {
            List<Callable<T>> told = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                int index = i;
                Callable<T> task = tasks.get(i);
                told.add(() -> {
                    Result<T> result;
                    try {
                        result = new Result<>(task.call(), null);
                    } catch (Exception e) {
                        result = new Result<>(null, e);
                    }
                    synchronized (each) {
                        each.done(index, result);
                    }
                    if (!result.ok()) throw result.error;
                    return result.value;
                });
            }
            tasks = told;
        }
        Background.Tasks pool = new Background.Tasks(Math.min(MAX_THREADS, tasks.size()));
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) futures.add(pool.submit(task));
            long deadline = System.currentTimeMillis() + timeoutMs;
            for (Future<T> f : futures) {
                try {
                    long wait = Math.max(0, deadline - System.currentTimeMillis());
                    results.add(new Result<>(f.get(wait, TimeUnit.MILLISECONDS), null));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    results.add(new Result<>(null, cause instanceof Exception
                            ? (Exception) cause : new Exception(cause)));
                } catch (TimeoutException e) {
                    f.cancel(true);
                    results.add(new Result<>(null, new StremioException("took too long")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(new Result<>(null, e));
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
