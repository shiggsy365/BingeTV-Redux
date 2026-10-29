package com.shiggsy365.bingetvredux.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class StremioUrlsTest {
    @Test
    public void normalize() throws Exception {
        String[][] cases = {
                {"https://a.com/manifest.json", "https://a.com/manifest.json"},
                {"https://a.com", "https://a.com/manifest.json"},
                {"https://a.com/cfg/", "https://a.com/cfg/manifest.json"},
                {"  stremio://a.com/x%7Cy/manifest.json ", "https://a.com/x%7Cy/manifest.json"},
                {"a.com/manifest.json", "https://a.com/manifest.json"},
                {"http://127.0.0.1:7000/manifest.json", "http://127.0.0.1:7000/manifest.json"},
                {"https://a.com/manifest.json?bcv=48", "https://a.com/manifest.json?bcv=48"},
        };
        for (String[] c : cases) assertEquals(c[1], StremioUrls.normalize(c[0]));
    }

    @Test
    public void normalizeRejectsBadUrls() {
        for (String bad : new String[]{"", "   ", "ftp://a.com/manifest.json", "https://"}) {
            try {
                StremioUrls.normalize(bad);
                fail("accepted " + bad);
            } catch (StremioException expected) {
                // ok
            }
        }
    }

    @Test
    public void baseUrl() {
        assertEquals("https://a.com/cfg=abc", StremioUrls.baseUrl("https://a.com/cfg=abc/manifest.json"));
        assertEquals("https://a.com", StremioUrls.baseUrl("https://a.com/manifest.json"));
        assertEquals("https://a.com", StremioUrls.baseUrl("https://a.com/manifest.json?bcv=1"));
    }

    @Test
    public void resourceUrl() {
        assertEquals("https://a.com/meta/movie/tt1.json",
                StremioUrls.resourceUrl("https://a.com/manifest.json", "meta", "movie", "tt1", null));
        assertEquals("https://a.com/stream/series/tt1%3A1%3A2.json",
                StremioUrls.resourceUrl("https://a.com/manifest.json", "stream", "series", "tt1:1:2", null));
        List<Map.Entry<String, String>> extra = Arrays.asList(
                new SimpleEntry<>("genre", "Sci-Fi & Fantasy"), new SimpleEntry<>("skip", "100"),
                new SimpleEntry<>("search", null));
        assertEquals("https://a.com/catalog/movie/top/genre=Sci-Fi%20%26%20Fantasy&skip=100.json",
                StremioUrls.resourceUrl("https://a.com/manifest.json", "catalog", "movie", "top", extra));
        // The manifest's query string is kept, as Stremio does.
        assertEquals("https://a.com/meta/movie/tt1.json?bcv=48",
                StremioUrls.resourceUrl("https://a.com/manifest.json?bcv=48", "meta", "movie", "tt1", null));
    }
}
