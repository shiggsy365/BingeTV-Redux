package com.shiggsy365.bingetvredux.stremio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CatalogsTest {
    @Test
    public void paging() throws Exception {
        Manifest.Catalog top = Fixtures.manifest(Fixtures.CINEMETA_LIKE).catalog("movie/top");
        // Cinemeta sends 49 of a page of 50: still a full page.
        assertArrayEquals(new int[]{50, 50}, Catalogs.nextPage(top, 0, 49, 0));
        assertArrayEquals(new int[]{100, 50}, Catalogs.nextPage(top, 50, 48, 50));
        assertNull(Catalogs.nextPage(top, 100, 12, 50));
        assertNull(Catalogs.nextPage(top, 0, 7, 0));
        Manifest.Catalog series = Fixtures.manifest(Fixtures.CINEMETA_LIKE).catalog("series/top");
        assertNull(Catalogs.nextPage(series, 0, 50, 0));
    }

    @Test
    public void extrasInManifestOrderWithDefaults() throws Exception {
        Manifest m = Manifest.fromJson(new JSONObject("{\"id\":\"x\",\"name\":\"X\",\"resources\":[],"
                + "\"catalogs\":[{\"type\":\"movie\",\"id\":\"year\",\"extra\":["
                + "{\"name\":\"genre\",\"isRequired\":true,\"options\":[\"2026\",\"2025\"]},"
                + "{\"name\":\"skip\"}]}]}"));
        Manifest.Catalog c = m.catalog("movie/year");
        Map<String, String> filters = Catalogs.withDefaultFilters(c, new LinkedHashMap<>());
        assertEquals("2026", filters.get("genre"));
        List<Map.Entry<String, String>> extra = Catalogs.orderedExtra(c, filters, 100);
        assertEquals("genre", extra.get(0).getKey());
        assertEquals("skip", extra.get(1).getKey());
        assertEquals("100", extra.get(1).getValue());
    }

    @Test
    public void metaParsing() throws Exception {
        MetaItem m = MetaItem.full(new JSONObject("{\"id\":\"tt1\",\"type\":\"series\",\"name\":\"Show\","
                + "\"releaseInfo\":\"2019–2023\",\"imdbRating\":\"8.4\",\"runtime\":\"1h 5min\","
                + "\"background\":\"https://image.tmdb.org/t/p/original/b.jpg\","
                + "\"cast\":[\"A, B\"],\"director\":[{\"name\":\"D\"}],"
                + "\"trailers\":[{\"source\":\"yt1\",\"type\":\"Trailer\"}],"
                + "\"videos\":[{\"id\":\"tt1:2:1\",\"season\":2,\"episode\":1},"
                + "{\"id\":\"tt1:0:1\",\"season\":0,\"episode\":1},"
                + "{\"id\":\"tt1:1:2\",\"season\":\"1\",\"number\":2},"
                + "{\"id\":\"tt1:1:1\",\"season\":1,\"episode\":1,\"imdbRating\":\"0\"}]}"), null);
        assertEquals(2019, m.year());
        assertEquals(8.4, m.imdbRating, 0.001);
        assertEquals(3900, m.runtimeSeconds());
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", m.background);
        assertEquals(2, m.people(MetaItem.CAST).size());
        assertEquals("D", m.people(MetaItem.DIRECTOR).get(0).name);
        assertEquals("yt1", m.trailer);
        assertEquals(java.util.Arrays.asList(1, 2, 0), m.seasons());
        assertEquals("tt1:1:1", m.episodes(1).get(0).id);
        assertEquals(0, m.episodes(1).get(0).rating, 0);
        assertEquals("1396", MetaItem.externalIds("tmdb:1396", null).get("tmdb"));
    }
}
