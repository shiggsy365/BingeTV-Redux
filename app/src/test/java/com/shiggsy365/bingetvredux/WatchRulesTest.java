package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** When playing counts as watched, as a resume point, or as nothing yet. */
public class WatchRulesTest {
    private static final long HOUR = 3600_000L;

    @Test
    public void watchedFromThePercent() {
        assertEquals(WatchStore.WATCHED, WatchStore.outcome(54 * 60_000L, HOUR, 90));
        assertEquals(WatchStore.RESUME, WatchStore.outcome(53 * 60_000L, HOUR, 90));
    }

    @Test
    public void resumeOnlyAfterTwoMinutes() {
        assertEquals(WatchStore.NONE, WatchStore.outcome(119_000, HOUR, 90));
        assertEquals(WatchStore.RESUME, WatchStore.outcome(120_000, HOUR, 90));
    }

    @Test
    public void unknownLengthIsNothing() {
        assertEquals(WatchStore.NONE, WatchStore.outcome(10 * 60_000L, 0, 90));
    }
}
