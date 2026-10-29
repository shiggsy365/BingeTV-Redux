package com.shiggsy365.bingetvredux.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AddonStoreTest {
    private static final String META_URL = "https://meta.example/manifest.json";
    private static final String STREAM_URL = "https://streams.example/token=abc/manifest.json";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File file;
    private AddonStore store;

    @Before
    public void setUp() throws Exception {
        file = new File(tmp.getRoot(), "addons.json");
        store = new AddonStore(file);
        store.add(META_URL, Fixtures.manifest(Fixtures.CINEMETA_LIKE), 1);
        store.add(STREAM_URL, Fixtures.manifest(Fixtures.STREAM_ADDON), 1);
    }

    private AddonStore reload() {
        return new AddonStore(file);
    }

    private static List<String> urls(List<AddonStore.Addon> addons) {
        List<String> out = new ArrayList<>();
        for (AddonStore.Addon a : addons) out.add(a.transportUrl);
        return out;
    }

    private static List<String> keys(List<AddonStore.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (AddonStore.Entry e : entries) out.add(e.catalog.key());
        return out;
    }

    @Test
    public void addPersists() throws Exception {
        assertEquals(Arrays.asList(META_URL, STREAM_URL), urls(reload().all()));
        assertEquals("Example Streams", reload().get(STREAM_URL).manifest.name);
    }

    @Test
    public void duplicateUrlRejectedButOtherConfigAllowed() throws Exception {
        try {
            store.add(META_URL, Fixtures.manifest(Fixtures.CINEMETA_LIKE), 2);
            fail();
        } catch (StremioException expected) {
            // ok
        }
        store.add("https://streams.example/token=xyz/manifest.json",
                Fixtures.manifest(Fixtures.STREAM_ADDON), 2);
        assertEquals(3, store.all().size());
    }

    @Test
    public void moveIsClampedAndSaved() throws Exception {
        store.move(STREAM_URL, -5);
        assertEquals(Arrays.asList(STREAM_URL, META_URL), urls(reload().all()));
        store.move(STREAM_URL, 10);
        assertEquals(Arrays.asList(META_URL, STREAM_URL), urls(store.all()));
    }

    @Test
    public void disabledAddonsAreNotUsed() throws Exception {
        assertEquals(1, store.addonsFor("stream", "movie", "tt1").size());
        store.setEnabled(STREAM_URL, false);
        assertTrue(store.addonsFor("stream", "movie", "tt1").isEmpty());
        assertFalse(reload().get(STREAM_URL).enabled);
    }

    @Test
    public void hubsAndCatalogPrefs() throws Exception {
        assertEquals(Arrays.asList("movie/top"), keys(store.hubCatalogs("movies")));
        assertEquals(Arrays.asList("series/top"), keys(store.hubCatalogs("tvshows")));
        assertEquals(Arrays.asList("movie/search"), keys(store.searchCatalogs()));
        String kids = store.createHub("Kids");
        String key = store.get(META_URL).key() + "|series/top";
        store.setCatalogPrefs(key, null, null, "Shows", null, kids);
        AddonStore loaded = reload();
        assertEquals(Arrays.asList("series/top"), keys(loaded.hubCatalogs(kids)));
        assertEquals("Shows", loaded.entry(key).addon.displayName(loaded.entry(key).catalog));
        assertTrue(loaded.hubCatalogs("tvshows").isEmpty());
        loaded.removeHub(kids);
        assertEquals(Arrays.asList("series/top"), keys(loaded.hubCatalogs("tvshows")));
    }

    @Test
    public void updateManifestDropsStalePrefs() throws Exception {
        String metaKey = store.get(META_URL).key();
        store.setCatalogPrefs(metaKey + "|series/top", false, null, null, null, null);
        JSONObject trimmed = new JSONObject(Fixtures.CINEMETA_LIKE).put("version", "2.0.0");
        trimmed.put("catalogs", new org.json.JSONArray().put(trimmed.getJSONArray("catalogs").get(0)));
        store.updateManifest(META_URL, Manifest.fromJson(trimmed), 2);
        AddonStore.Addon addon = reload().get(META_URL);
        assertEquals("2.0.0", addon.manifest.version);
        assertEquals(1, addon.manifest.catalogs.size());
    }

    @Test
    public void replaceUrlKeepsPlaceAndPrefs() throws Exception {
        String oldKey = store.get(META_URL).key();
        store.setCatalogPrefs(oldKey + "|movie/top", null, null, "Films", null, null);
        store.moveInHub(oldKey + "|movie/top", 0);
        String newUrl = "https://meta.example/new/manifest.json";
        store.replaceUrl(META_URL, newUrl, Fixtures.manifest(Fixtures.CINEMETA_LIKE), 3);
        AddonStore loaded = reload();
        assertEquals(Arrays.asList(newUrl, STREAM_URL), urls(loaded.all()));
        AddonStore.Entry e = loaded.entry(loaded.get(newUrl).key() + "|movie/top");
        assertEquals("Films", e.addon.displayName(e.catalog));
    }

    @Test
    public void moveInHubKeepsOtherHubs() throws Exception {
        store.add("https://two.example/manifest.json", Fixtures.manifest(
                Fixtures.CINEMETA_LIKE.replace("com.example.meta", "com.example.two")), 1);
        String second = store.get("https://two.example/manifest.json").key() + "|movie/top";
        store.moveInHub(second, -1);
        List<AddonStore.Entry> movies = reload().hubCatalogs("movies");
        assertEquals("https://two.example/manifest.json", movies.get(0).addon.transportUrl);
        assertEquals(META_URL, reload().hubCatalogs("tvshows").get(0).addon.transportUrl);
    }

    @Test
    public void tidyName() {
        assertEquals("Netflix", AddonStore.tidyName("Netflix 🍿"));
        assertEquals("🍿", AddonStore.tidyName("🍿"));
    }
}
