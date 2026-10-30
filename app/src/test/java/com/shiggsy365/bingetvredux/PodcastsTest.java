package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Reading feeds' lengths and dates, which podcasts write in several ways. */
public class PodcastsTest {
    @Test
    public void readsDurations() {
        assertEquals(3723, Podcasts.seconds("1:02:03"));
        assertEquals(3723, Podcasts.seconds("62:03"));
        assertEquals(3723, Podcasts.seconds("3723"));
        assertEquals(0, Podcasts.seconds(""));
        assertEquals(0, Podcasts.seconds("about an hour"));
    }

    @Test
    public void readsDates() {
        // Tue, 10 Jun 2025 04:00:00 GMT
        assertEquals(1749528000000L, Podcasts.date("Tue, 10 Jun 2025 04:00:00 +0000"));
        assertEquals(1749528000000L, Podcasts.date("Tue, 10 Jun 2025 04:00:00 GMT"));
        assertEquals(1749528000000L, Podcasts.date("10 Jun 2025 04:00:00 +0000"));
        assertEquals(0, Podcasts.date("soon"));
    }
}
