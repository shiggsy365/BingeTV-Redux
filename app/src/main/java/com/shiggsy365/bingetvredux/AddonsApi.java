package com.shiggsy365.bingetvredux;

import android.content.Context;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.Manifest;
import com.shiggsy365.bingetvredux.stremio.StremioClient;
import com.shiggsy365.bingetvredux.stremio.StremioException;
import com.shiggsy365.bingetvredux.stremio.StremioUrls;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The web page's Stremio addon endpoints (see WebServer for the rest):
 *
 *   GET  /api/addons                     installed addons, in order
 *   POST /api/addons                     {url} — fetches the manifest and adds the addon
 *   POST /api/addons/update              {key, enabled?, move?: -1|1, url?} — a new url
 *                                        replaces a reconfigured addon in place
 *   POST /api/addons/delete              {key}
 *   POST /api/addons/refresh             {key?} — refetches one manifest, or all of them
 */
final class AddonsApi {
    private final Context mContext;

    AddonsApi(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Handles {@code uri}, or returns null if it isn't one of these endpoints. */
    Object route(String uri, Map<String, String> params, JSONObject body) throws Exception {
        switch (uri) {
            case "/api/addons":
                return body == null ? list() : add(body.optString("url"));
            case "/api/addons/update":
                return update(body);
            case "/api/addons/delete":
                store().remove(body.getString("key"));
                return list();
            case "/api/addons/refresh":
                return refresh(body.optString("key", ""));
            case "/api/debug/stremio":
                if (!BuildConfig.DEBUG) return null;
                return debug(params.get("type"), params.get("id"));
            default:
                return null;
        }
    }

    private AddonStore store() {
        return Stremio.addons(mContext);
    }

    private JSONObject list() throws JSONException {
        JSONArray addons = new JSONArray();
        for (AddonStore.Addon a : store().all()) addons.put(addonJson(a));
        return new JSONObject().put("addons", addons);
    }

    private JSONObject add(String url) throws JSONException {
        try {
            StremioClient.Fetched fetched = Stremio.client(mContext).fetchManifest(url);
            if (fetched.manifest.configurationRequired) {
                throw new IllegalArgumentException(fetched.manifest.name + " needs configuring "
                        + "first. Open its configure page, then paste the URL it gives you.");
            }
            store().add(fetched.transportUrl, fetched.manifest, System.currentTimeMillis());
            return list();
        } catch (StremioException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private JSONObject update(JSONObject body) throws JSONException {
        String key = body.getString("key");
        try {
            if (body.has("enabled")) store().setEnabled(key, body.getBoolean("enabled"));
            if (body.has("move")) store().move(key, body.getInt("move"));
            if (!body.optString("url").isEmpty()) {
                StremioClient.Fetched fetched = Stremio.client(mContext).fetchManifest(body.getString("url"));
                store().replaceUrl(key, fetched.transportUrl, fetched.manifest,
                        System.currentTimeMillis());
            }
            return list();
        } catch (StremioException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /** Refetches manifests in parallel; addons that fail keep the manifest they had. */
    private JSONObject refresh(String key) throws Exception {
        List<AddonStore.Addon> addons = new ArrayList<>();
        if (key.isEmpty()) addons.addAll(store().all());
        else addons.add(store().get(key));
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(6, addons.size())));
        try {
            List<Future<Manifest>> results = new ArrayList<>();
            for (AddonStore.Addon a : addons) {
                results.add(pool.submit(() -> Stremio.client(mContext).fetchManifest(a.transportUrl).manifest));
            }
            JSONArray failed = new JSONArray();
            for (int i = 0; i < addons.size(); i++) {
                AddonStore.Addon a = addons.get(i);
                try {
                    store().updateManifest(a.key(), results.get(i).get(),
                            System.currentTimeMillis());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    failed.put(a.manifest.name + ": " + (cause != null ? cause.getMessage() : "failed"));
                }
            }
            return list().put("failed", failed);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Debug builds: what the addons give for a title, to check the client against them. */
    private JSONObject debug(String type, String id) throws Exception {
        StremioClient client = Stremio.client(mContext);
        JSONObject out = new JSONObject();
        long t0 = System.currentTimeMillis();
        com.shiggsy365.bingetvredux.stremio.Metas.Found found =
                com.shiggsy365.bingetvredux.stremio.Metas.fetch(client, store(), type, id);
        out.put("meta", found.meta.name + " (" + found.meta.year() + ") from " + found.addonName)
                .put("seasons", new JSONArray(found.meta.seasons()))
                .put("videos", found.meta.videos.size())
                .put("cast", found.meta.people(com.shiggsy365.bingetvredux.stremio.MetaItem.CAST).size())
                .put("metaMs", System.currentTimeMillis() - t0);
        String videoId = found.meta.hasSeasons() && !found.meta.videos.isEmpty()
                ? found.meta.episodes(found.meta.seasons().get(0)).get(0).id : id;
        t0 = System.currentTimeMillis();
        com.shiggsy365.bingetvredux.stremio.Streams.Found streams =
                com.shiggsy365.bingetvredux.stremio.Streams.fetch(client, store(), type, videoId);
        List<com.shiggsy365.bingetvredux.stremio.Stream> ready =
                com.shiggsy365.bingetvredux.stremio.Streams.prepare(streams.streams,
                        new com.shiggsy365.bingetvredux.stremio.Streams.Prefs());
        JSONArray top = new JSONArray();
        for (int i = 0; i < Math.min(5, ready.size()); i++) {
            top.put(ready.get(i).addon + ": " + String.join(" · ",
                    com.shiggsy365.bingetvredux.stremio.Streams.tags(ready.get(i))));
        }
        out.put("videoId", videoId).put("streams", streams.streams.size())
                .put("playable", ready.size()).put("top", top)
                .put("streamErrors", new JSONArray(streams.errors))
                .put("streamsMs", System.currentTimeMillis() - t0);
        t0 = System.currentTimeMillis();
        out.put("subtitles", com.shiggsy365.bingetvredux.stremio.Subtitles.fetch(client, store(),
                type, videoId, ready.isEmpty() ? null : ready.get(0),
                java.util.Collections.singletonList("eng")).size())
                .put("subtitlesMs", System.currentTimeMillis() - t0);
        return out;
    }

    private static JSONObject addonJson(AddonStore.Addon a) throws JSONException {
        Manifest m = a.manifest;
        int browsable = 0, searchable = 0;
        for (Manifest.Catalog c : m.catalogs) {
            if (c.isBrowsable()) browsable++;
            if (c.isSearchableAlone()) searchable++;
        }
        return new JSONObject()
                .put("key", a.key())
                .put("url", a.transportUrl)
                .put("host", host(a.transportUrl))
                .put("name", m.name)
                .put("version", m.version)
                .put("description", m.description)
                .put("logo", m.logo)
                .put("enabled", a.enabled)
                .put("types", new JSONArray(m.types))
                .put("resources", new JSONArray(m.resourceNames()))
                .put("catalogs", browsable)
                .put("searchCatalogs", searchable)
                .put("configureUrl", m.configurable ? StremioUrls.configureUrl(a.transportUrl) : "")
                .put("p2p", m.p2p)
                .put("fetchedAt", a.fetchedAt);
    }

    private static String host(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (java.net.URISyntaxException e) {
            return "";
        }
    }
}
