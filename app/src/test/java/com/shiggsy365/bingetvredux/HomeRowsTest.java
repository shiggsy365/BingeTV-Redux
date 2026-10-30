package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Video;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Next Up's choice of episode, and the combined Continue Watching row's order. */
public class HomeRowsTest {
    private static final String TODAY = "2026-10-30";

    /** A show with episodes "season:episode:released", e.g. "1:2:2026-01-08". */
    private static MetaItem show(String... episodes) throws Exception {
        JSONArray videos = new JSONArray();
        for (String e : episodes) {
            String[] p = e.split(":");
            videos.put(new JSONObject().put("id", "tt1:" + p[0] + ":" + p[1])
                    .put("season", Integer.parseInt(p[0])).put("episode", Integer.parseInt(p[1]))
                    .put("released", p[2]));
        }
        return MetaItem.full(new JSONObject().put("id", "tt1").put("type", "series")
                .put("name", "Show").put("videos", videos), "series");
    }

    private static Set<String> watched(String... codes) {
        return new HashSet<>(Arrays.asList(codes));
    }

    @Test
    public void nextIsAfterTheFurthestWatched() throws Exception {
        MetaItem show = show("1:1:2026-01-01", "1:2:2026-01-08", "1:3:2026-01-15", "2:1:2026-06-01");
        // E1 skipped: still the one after the furthest watched.
        Video next = HomeRows.next(show, watched("1:2"), TODAY);
        assertEquals(3, next.episode);
        // The end of a season runs on into the next.
        next = HomeRows.next(show, watched("1:1", "1:2", "1:3"), TODAY);
        assertEquals(2, next.season);
        assertEquals(1, next.episode);
    }

    @Test
    public void nothingNextWhenUnstartedFinishedOrUnaired() throws Exception {
        MetaItem show = show("1:1:2026-01-01", "1:2:2026-12-25");
        assertNull(HomeRows.next(show, watched(), TODAY));
        assertNull(HomeRows.next(show, watched("1:1"), TODAY));
        assertNull(HomeRows.next(show, watched("1:1", "1:2"), TODAY));
    }

    @Test
    public void specialsDontCount() throws Exception {
        MetaItem show = show("0:1:2025-12-01", "1:1:2026-01-01", "1:2:2026-01-08");
        assertNull(HomeRows.next(show, watched("0:1"), TODAY));
        assertEquals(2, HomeRows.next(show, watched("1:1"), TODAY).episode);
    }

    @Test
    public void combinedRowIsNewestFirst() throws Exception {
        MetaItem a = show("1:1:2026-01-01"), b = show("1:1:2026-01-01"), c = show("1:1:2026-01-01");
        List<MetaItem> row = HomeRows.newestFirst(
                Arrays.asList(new HomeRows.Timed(100, a), new HomeRows.Timed(300, c)),
                Collections.singletonList(new HomeRows.Timed(200, b)));
        assertEquals(Arrays.asList(c, b, a), row);
    }
}
