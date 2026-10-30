package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The shared pool: each owner's limit and order, and cancelling only its own work. */
public class BackgroundTest {
    @Test
    public void oneAtATimeRunsInOrder() throws Exception {
        Background.Tasks tasks = new Background.Tasks(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            int n = i;
            tasks.execute(() -> {
                order.add(n);
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 20; i++) assertEquals(i, (int) order.get(i));
    }

    @Test
    public void neverMoreThanTheLimit() throws Exception {
        Background.Tasks tasks = new Background.Tasks(3);
        AtomicInteger running = new AtomicInteger(), most = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(12);
        for (int i = 0; i < 12; i++) {
            tasks.execute(() -> {
                most.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // Finishing anyway.
                }
                running.decrementAndGet();
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(most.get() <= 3);
    }

    @Test
    public void shutdownCancelsOnlyItsOwn() throws Exception {
        Background.Tasks mine = new Background.Tasks(1), theirs = new Background.Tasks(1);
        CountDownLatch started = new CountDownLatch(1);
        Future<String> slow = mine.submit(() -> {
            started.countDown();
            Thread.sleep(10_000);
            return "late";
        });
        Future<String> other = theirs.submit(() -> "ok");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        mine.shutdownNow();
        assertTrue(slow.isCancelled() || slow.isDone());
        assertEquals("ok", other.get(5, TimeUnit.SECONDS));
        // Nothing more is taken once shut down.
        assertTrue(mine.submit(() -> "never").isCancelled());
    }
}
