package com.shiggsy365.bingetvredux.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StreamsTest {
    private static Stream stream(String json, int addonIndex) throws Exception {
        return Stream.fromJson(new JSONObject(json), "Addon " + addonIndex, addonIndex);
    }

    @Test
    public void parsesQuality() throws Exception {
        Stream s = stream("{\"name\":\"[RD+] AIO 4K\",\"title\":\"Film.2023.2160p.DV.HDR10+.REMUX.HEVC\\n💾 58.2 GB\","
                + "\"url\":\"https://x/y.mkv\",\"behaviorHints\":{\"filename\":\"Film.mkv\","
                + "\"proxyHeaders\":{\"request\":{\"Referer\":\"https://r\"}}}}", 0);
        Stream.Quality q = s.quality;
        assertEquals(2160, q.resolution);
        assertEquals(Arrays.asList("DV", "HDR10+"), q.hdr);
        assertEquals("HEVC", q.codec);
        assertTrue(q.remux);
        assertTrue(q.cached);
        assertEquals((long) (58.2 * (1L << 30)), q.size);
        assertEquals("https://r", s.headers.get("Referer"));
        assertEquals(Arrays.asList("4K", "DV", "HDR10+", "REMUX", "HEVC", "58.2 GB", "Cached"),
                Streams.tags(s));
    }

    @Test
    public void labelResolutionWins() throws Exception {
        assertEquals(1080, stream("{\"name\":\"1080p\",\"title\":\"upscaled from 4K\",\"url\":\"u\"}", 0)
                .quality.resolution);
    }

    @Test
    public void unplayableEntriesAreDropped() throws Exception {
        assertNull(stream("{\"name\":\"nothing\"}", 0));
    }

    @Test
    public void prepareFiltersDedupesAndSorts() throws Exception {
        List<Stream> all = new ArrayList<>();
        all.add(stream("{\"name\":\"720p\",\"url\":\"a\"}", 0));
        all.add(stream("{\"name\":\"1080p\",\"title\":\"2 GB\",\"url\":\"b\"}", 0));
        all.add(stream("{\"name\":\"1080p [RD+]\",\"title\":\"1 GB\",\"url\":\"c\"}", 1));
        all.add(stream("{\"name\":\"1080p dup\",\"url\":\"b\"}", 1));
        all.add(stream("{\"name\":\"HDCAM 1080p\",\"url\":\"d\"}", 0));
        all.add(stream("{\"name\":\"4K\",\"url\":\"e\"}", 0));
        all.add(stream("{\"name\":\"torrent\",\"infoHash\":\"ABC\"}", 0));
        all.add(stream("{\"name\":\"1080p FRENCH\",\"url\":\"f\"}", 0));
        Streams.Prefs prefs = new Streams.Prefs();
        prefs.maxResolution = 1080;
        prefs.exclude = Streams.Prefs.parseKeywords(" french , ");
        List<String> urls = new ArrayList<>();
        for (Stream s : Streams.prepare(all, prefs)) urls.add(s.url);
        // Cached first among equal resolutions, then bigger.
        assertEquals(Arrays.asList("c", "b", "a"), urls);
        prefs.sort = Streams.SORT_ADDON;
        urls.clear();
        for (Stream s : Streams.prepare(all, prefs)) urls.add(s.url);
        assertEquals(Arrays.asList("a", "b", "c"), urls);
    }

    @Test
    public void cleanText() {
        assertEquals("a b\nc", Stream.cleanText("a​   b\n\n  c  "));
    }

    @Test
    public void subtitleLanguages() {
        assertTrue(Subtitles.sameLanguage("eng", "en"));
        assertTrue(Subtitles.sameLanguage("English", "eng"));
        assertTrue(Subtitles.sameLanguage("ger", "deu"));
        assertFalse(Subtitles.sameLanguage("", ""));
        assertFalse(Subtitles.sameLanguage("fre", "eng"));
    }
}
