package com.shiggsy365.bingetvredux.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class ManifestTest {
    @Test
    public void parsesCatalogs() throws Exception {
        Manifest m = Fixtures.manifest(Fixtures.CINEMETA_LIKE);
        assertEquals("Example Meta", m.name);
        assertEquals(3, m.catalogs.size());
        Manifest.Catalog top = m.catalog("movie/top");
        assertTrue(top.isBrowsable());
        assertTrue(top.supportsPaging());
        assertEquals(Arrays.asList("Action", "Sci-Fi"), top.filters().get(0).options);
        Manifest.Catalog search = m.catalog("movie/search");
        assertFalse(search.isBrowsable());
        assertTrue(search.isSearchableAlone());
        assertTrue(search.isSearchOnly());
        assertFalse(search.defaultOnHome());
    }

    @Test
    public void supportsUsesResourceOrManifestLimits() throws Exception {
        Manifest meta = Fixtures.manifest(Fixtures.CINEMETA_LIKE);
        assertTrue(meta.supports("meta", "movie", "tt1"));
        assertFalse(meta.supports("meta", "movie", "kitsu:1"));
        assertFalse(meta.supports("stream", "movie", "tt1"));
        Manifest streams = Fixtures.manifest(Fixtures.STREAM_ADDON);
        assertTrue(streams.supports("stream", "series", "kitsu:5"));
        assertFalse(streams.supports("stream", "anime", "kitsu:5"));
        assertTrue(streams.p2p);
        assertTrue(streams.configurable);
    }

    @Test
    public void legacyExtrasAndIsSearch() throws Exception {
        Manifest m = Manifest.fromJson(new JSONObject("{\"id\":\"x\",\"name\":\"X\",\"resources\":[],"
                + "\"catalogs\":[{\"type\":\"movie\",\"id\":\"a\",\"extraSupported\":[\"genre\"],"
                + "\"genres\":[\"Drama\"]},{\"type\":\"movie\",\"id\":\"s\",\"isSearch\":true},"
                + "{\"id\":\"no-type\"}]}"));
        assertEquals(2, m.catalogs.size());
        assertEquals("a", m.catalog("movie/a").name);
        assertEquals(Arrays.asList("Drama"), m.catalog("movie/a").extra("genre").options);
        assertTrue(m.catalog("movie/s").isSearchOnly());
        assertNull(m.idPrefixes);
        assertEquals("0.0.0", m.version);
    }

    @Test
    public void rejectsNonManifests() throws Exception {
        for (String json : new String[]{"{\"hello\":\"world\"}", "{\"id\":\"a\",\"name\":\"A\"}"}) {
            try {
                Manifest.fromJson(new JSONObject(json));
                fail("accepted " + json);
            } catch (StremioException expected) {
                // ok
            }
        }
    }
}
