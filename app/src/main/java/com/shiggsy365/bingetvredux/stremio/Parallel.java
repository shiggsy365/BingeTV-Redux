package com.shiggsy365.bingetvredux.stremio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /**
     * Runs the tasks together and returns their results in task order. Tasks still running
     * after {@code timeoutMs} count as failed.
     */
    public static <T> List<Result<T>> run(List<Callable<T>> tasks, long timeoutMs) {
        List<Result<T>> results = new ArrayList<>();
        if (tasks.isEmpty()) return results;
        if (tasks.size() == 1) {
            try {
                results.add(new Result<>(tasks.get(0).call(), null));
            } catch (Exception e) {
                results.add(new Result<>(null, e));
            }
            return results;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_THREADS, tasks.size()));
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
