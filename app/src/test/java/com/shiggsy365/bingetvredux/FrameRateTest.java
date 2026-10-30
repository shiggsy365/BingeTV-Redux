package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Measured frame rates come out as the standard rate they are. */
public class FrameRateTest {
    @Test
    public void snapsToTheNearestStandardRate() {
        assertEquals(23.976f, FrameRateMatcher.snap(23.98f), 0.0001f);
        assertEquals(24f, FrameRateMatcher.snap(24.01f), 0.0001f);
        assertEquals(25f, FrameRateMatcher.snap(25.1f), 0.0001f);
        assertEquals(59.94f, FrameRateMatcher.snap(59.95f), 0.0001f);
        assertEquals(60f, FrameRateMatcher.snap(60.02f), 0.0001f);
        // Nothing standard nearby: as measured.
        assertEquals(15f, FrameRateMatcher.snap(15f), 0.0001f);
    }
}
