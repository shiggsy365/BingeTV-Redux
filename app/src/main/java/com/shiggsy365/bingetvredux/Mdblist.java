package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.MetaItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MDBList (api.mdblist.com), with each profile's API key: scrobbling what's played, the
 * watchlist (a Home row, and add/remove on info pages), and watched-history sync both ways.
 * A pull only adds watched items: it never unwatches anything here.
 */
public final class Mdblist {
    private static final String TAG = "Mdblist";
    private static final String BASE = "https://api.mdblist.com";
    private static final int TIMEOUT_MS = 15_000;
    private static final long WATCHLIST_TTL_MS = 10 * 60_000L;
    private static final long SYNC_EVERY_MS = 6 * 3600_000L;
    private static final int BATCH = 100;
    public static final String START = "start", PAUSE = "pause", STOP = "stop";
    private static final List<String> MOVIE_IDS = Arrays.asList("imdb", "tmdb", "trakt", "kitsu", "mdblist");
    private static final List<String> SHOW_IDS = Arrays.asList("imdb", "tmdb", "trakt", "tvdb", "mdblist");

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static List<MetaItem> sWatchlist;
    private static long sWatchlistAt;
    private static String sWatchlistKey = "";

    private Mdblist() {}

    public static final class MdblistException extends Exception {
        MdblistException(String message) {
            super(message);
        }
    }

    private static String key(Context context) {
        return Settings.string(context, Settings.MDBLIST_KEY);
    }

    public static boolean enabled(Context context) {
        return !key(context).isEmpty();
    }

    // ---- Requests ----

    private static Object request(String key, String method, String path, Map<String, String> params,
            JSONObject body) throws MdblistException {
        Uri.Builder url = Uri.parse(BASE + path).buildUpon().appendQueryParameter("apikey", key);
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() != null) url.appendQueryParameter(e.getKey(), e.getValue());
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url.build().toString()).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            String type = conn.getContentType() == null ? "" : conn.getContentType();
            if ((status == 401 || status == 403) && type.contains("json")) {
                throw new MdblistException("MDBList rejected the API key");
            }
            if (status >= 400) throw new MdblistException("MDBList answered HTTP " + status);
            String text = read(conn.getInputStream());
            if (text.isEmpty()) return new JSONObject();
            return text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        } catch (IOException e) {
            throw new MdblistException("Couldn't reach MDBList: " + e.getMessage());
        } catch (JSONException e) {
            throw new MdblistException("MDBList's answer isn't JSON");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Every page of a paged endpoint, its lists merged. */
    private static JSONObject paged(String key, String path, Map<String, String> params)
            throws MdblistException, JSONException {
        JSONObject merged = new JSONObject();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            Map<String, String> p = new HashMap<>(params);
            p.put("limit", "1000");
            if (cursor != null) p.put("cursor", cursor);
            Object answer = request(key, "GET", path, p, null);
            if (!(answer instanceof JSONObject)) break;
            JSONObject data = (JSONObject) answer;
            for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray list = data.optJSONArray(k);
                if (list == null) continue;
                JSONArray into = merged.optJSONArray(k);
                if (into == null) merged.put(k, into = new JSONArray());
                for (int i = 0; i < list.length(); i++) into.put(list.get(i));
            }
            JSONObject pagination = data.optJSONObject("pagination");
            cursor = pagination == null ? null : pagination.optString("next_cursor", null);
            if (cursor == null || cursor.isEmpty() || "null".equals(cursor)) break;
        }
        return merged;
    }

    // ---- Ids ----

    private static JSONObject supported(JSONObject ids, boolean show) throws JSONException {
        JSONObject out = new JSONObject();
        for (String k : show ? SHOW_IDS : MOVIE_IDS) {
            Object v = ids.opt(k);
            if (v == null || v == JSONObject.NULL || String.valueOf(v).isEmpty()) continue;
            String s = String.valueOf(v);
            // MDBList wants numbers as numbers.
            out.put(k, k.equals("imdb") || !s.matches("\\d+") ? s : Long.parseLong(s));
        }
        return out;
    }

    /** The Stremio id addons most likely use for these ids (IMDb first). */
    static String stremioId(JSONObject ids) {
        String imdb = ids.optString("imdb", "");
        if (!imdb.isEmpty() && !"null".equals(imdb)) return imdb;
        for (String name : new String[]{"tmdb", "tvdb"}) {
            Object v = ids.opt(name);
            if (v != null && v != JSONObject.NULL && !String.valueOf(v).isEmpty()) return name + ":" + v;
        }
        return null;
    }

    // ---- Scrobbling ----

    /** Tells MDBList what's playing (start), paused or stopped. Fire and forget. */
    public static void scrobble(Context context, String event, WatchStore.Entry entry, int progress) {
        String key = key(context);
        if (key.isEmpty() || !Settings.bool(context, Settings.MDBLIST_SCROBBLE)) return;
        sExecutor.execute(() -> {
            try {
                JSONObject body;
                if (entry.isEpisode()) {
                    JSONObject ids = supported(entry.ids, true);
                    if (ids.length() == 0) return;
                    body = new JSONObject().put("show", new JSONObject().put("ids", ids)
                            .put("season", new JSONObject().put("number", entry.season)
                                    .put("episode", new JSONObject().put("number", entry.episode))));
                } else {
                    JSONObject ids = supported(entry.ids, false);
                    if (ids.length() == 0) return;
                    body = new JSONObject().put("movie", new JSONObject().put("ids", ids));
                }
                body.put("progress", Math.max(0, Math.min(100, progress)));
                body.put("app_version", BuildConfig.VERSION_NAME);
                request(key, "POST", "/scrobble/" + event, null, body);
            } catch (MdblistException | JSONException e) {
                Log.w(TAG, "Scrobble failed: " + e.getMessage());
            }
        });
    }

    // ---- Watchlist ----

    /** The watchlist as items for a row, cached for a few minutes. Off the main thread. */
    public static synchronized List<MetaItem> watchlist(Context context, boolean refresh)
            throws MdblistException {
        String key = key(context);
        if (key.isEmpty()) return new ArrayList<>();
        if (!refresh && sWatchlist != null && key.equals(sWatchlistKey)
                && System.currentTimeMillis() - sWatchlistAt < WATCHLIST_TTL_MS) {
            return sWatchlist;
        }
        List<MetaItem> items = new ArrayList<>();
        try {
            Map<String, String> params = new HashMap<>();
            params.put("append_to_response", "poster,description");
            JSONObject data = paged(key, "/watchlist/items", params);
            for (String kind : new String[]{"movies", "shows"}) {
                JSONArray list = data.optJSONArray(kind);
                boolean show = kind.equals("shows");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject e = list.getJSONObject(i);
                    JSONObject ids = e.optJSONObject("ids") != null
                            ? new JSONObject(e.getJSONObject("ids").toString()) : new JSONObject();
                    if (!ids.has("imdb") && !e.optString("imdb_id").isEmpty()) ids.put("imdb", e.optString("imdb_id"));
                    if (!ids.has("tvdb") && e.has("tvdb_id")) ids.put("tvdb", e.opt("tvdb_id"));
                    if (!ids.has("tmdb") && e.has("id")) ids.put("tmdb", e.opt("id"));
                    String id = stremioId(supported(ids, show));
                    if (id == null || e.optString("title").isEmpty()) continue;
                    MetaItem item = MetaItem.preview(new JSONObject()
                            .put("id", id).put("type", show ? "series" : "movie")
                            .put("name", e.optString("title"))
                            .put("poster", e.optString("poster", ""))
                            .put("releaseInfo", e.has("release_year") ? String.valueOf(e.opt("release_year")) : "")
                            .put("description", e.optString("description", "")), null);
                    if (item != null) items.add(item);
                }
            }
        } catch (JSONException e) {
            throw new MdblistException("MDBList's watchlist couldn't be read");
        }
        sWatchlist = items;
        sWatchlistAt = System.currentTimeMillis();
        sWatchlistKey = key;
        return items;
    }

    /** Whether a title is on the (cached) watchlist; false if it hasn't been loaded. */
    public static synchronized boolean onWatchlist(String id) {
        if (sWatchlist == null) return false;
        for (MetaItem m : sWatchlist) if (m.id.equals(id)) return true;
        return false;
    }

    /** Adds or removes a title. Off the main thread. */
    public static void setOnWatchlist(Context context, MetaItem item, boolean add)
            throws MdblistException {
        String key = key(context);
        if (key.isEmpty()) throw new MdblistException("Add an MDBList API key on the web page first");
        boolean show = !"movie".equals(item.type);
        try {
            JSONObject ids = new JSONObject();
            for (Map.Entry<String, String> e : item.ids.entrySet()) ids.put(e.getKey(), e.getValue());
            JSONObject usable = supported(ids, show);
            if (usable.length() == 0) throw new MdblistException("This title has no id MDBList knows");
            request(key, "POST", "/watchlist/items/" + (add ? "add" : "remove"), null,
                    new JSONObject().put(show ? "shows" : "movies",
                            new JSONArray().put(new JSONObject().put("ids", usable))));
        } catch (JSONException e) {
            throw new MdblistException(e.getMessage());
        }
        synchronized (Mdblist.class) {
            if (sWatchlist != null) {
                List<MetaItem> list = new ArrayList<>(sWatchlist);
                list.removeIf(m -> m.id.equals(item.id));
                if (add) list.add(0, item);
                sWatchlist = list;
            }
        }
    }

    // ---- Watched sync ----

    /** Checks the key; returns the account's latest activity time. */
    public static String test(String key) throws MdblistException {
        Object data = request(key, "GET", "/sync/last_activities", null, null);
        if (data instanceof JSONObject) {
            String watched = ((JSONObject) data).optString("watched_at", "");
            return watched.isEmpty() ? "The key works" : "The key works; last watched " + watched;
        }
        return "The key works";
    }

    /** Syncs if it's been a while (or {@code force}); quietly does nothing without a key. */
    public static void syncIfDue(Context context) {
        if (!enabled(context) || !Settings.bool(context, Settings.MDBLIST_SYNC)) return;
        SharedPreferences prefs = syncPrefs(context);
        if (System.currentTimeMillis() - prefs.getLong("syncedAt", 0) < SYNC_EVERY_MS) return;
        sExecutor.execute(() -> {
            try {
                sync(context, false);
            } catch (MdblistException e) {
                Log.w(TAG, "Sync failed: " + e.getMessage());
            }
        });
    }

    private static SharedPreferences syncPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                "mdblist_" + Profiles.current(context).id, Context.MODE_PRIVATE);
    }

    /**
     * Pushes watched items MDBList hasn't had, then pulls its watched history if it has
     * changed (or {@code force}). Returns a summary for the web page.
     */
    public static synchronized String sync(Context context, boolean force) throws MdblistException {
        String key = key(context);
        if (key.isEmpty()) throw new MdblistException("Add an MDBList API key first");
        WatchStore store = WatchStore.get(context);
        int pushed = 0, added = 0;
        try {
            List<WatchStore.Row> pending = store.unsyncedWatched();
            List<String> pendingIds = new ArrayList<>();
            for (int i = 0; i < pending.size(); i += BATCH) {
                JSONObject body = watchedBody(pending.subList(i, Math.min(pending.size(), i + BATCH)));
                if (body.length() > 0) request(key, "POST", "/sync/watched", null, body);
            }
            for (WatchStore.Row r : pending) {
                pendingIds.add(r.entry.videoId);
                if (supported(r.entry.ids, r.entry.isEpisode()).length() > 0) pushed++;
            }
            store.markSynced(pendingIds);

            SharedPreferences prefs = syncPrefs(context);
            Object activities = request(key, "GET", "/sync/last_activities", null, null);
            JSONObject marker = activities instanceof JSONObject
                    ? new JSONObject(activities.toString()) : new JSONObject();
            marker.remove("server_time");
            if (force || !pending.isEmpty() || !marker.toString().equals(prefs.getString("activities", ""))) {
                added = pull(key, store);
            }
            prefs.edit().putString("activities", marker.toString())
                    .putLong("syncedAt", System.currentTimeMillis()).apply();
        } catch (JSONException e) {
            throw new MdblistException("MDBList's answer couldn't be read");
        }
        return "Sent " + pushed + " watched, received " + added + " new";
    }

    private static int pull(String key, WatchStore store) throws MdblistException, JSONException {
        JSONObject data = paged(key, "/sync/watched", new HashMap<>());
        List<WatchStore.Entry> entries = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        JSONArray movies = data.optJSONArray("movies");
        for (int i = 0; movies != null && i < movies.length(); i++) {
            JSONObject e = movies.getJSONObject(i);
            JSONObject movie = e.optJSONObject("movie");
            if (movie == null) continue;
            JSONObject ids = supported(movie.optJSONObject("ids") == null ? new JSONObject()
                    : movie.getJSONObject("ids"), false);
            String id = stremioId(ids);
            if (id == null) continue;
            entries.add(new WatchStore.Entry(id, "movie", id, -1, -1, movie.optString("title"),
                    movie.optString("title"), "", "", "", "", ids));
            times.add(timestamp(e.optString("last_watched_at")));
        }
        JSONArray episodes = data.optJSONArray("episodes");
        for (int i = 0; episodes != null && i < episodes.length(); i++) {
            JSONObject e = episodes.getJSONObject(i);
            JSONObject episode = e.optJSONObject("episode");
            if (episode == null) continue;
            JSONObject show = episode.optJSONObject("show");
            if (show == null) continue;
            JSONObject ids = supported(show.optJSONObject("ids") == null ? new JSONObject()
                    : show.getJSONObject("ids"), true);
            String showId = stremioId(ids);
            if (showId == null || !episode.has("season") || !episode.has("number")) continue;
            int season = episode.optInt("season"), number = episode.optInt("number");
            entries.add(new WatchStore.Entry(showId + ":" + season + ":" + number, "series", showId,
                    season, number, episode.optString("title"), show.optString("title"), "", "", "",
                    "", ids));
            times.add(timestamp(e.optString("last_watched_at")));
        }
        return store.mergeRemote(entries, times);
    }

    private static JSONObject watchedBody(List<WatchStore.Row> rows) throws JSONException {
        JSONArray movies = new JSONArray();
        Map<String, JSONObject> shows = new HashMap<>();
        Map<String, Map<Integer, JSONArray>> seasons = new HashMap<>();
        for (WatchStore.Row r : rows) {
            WatchStore.Entry e = r.entry;
            JSONObject ids = supported(e.ids, e.isEpisode());
            if (ids.length() == 0) continue;
            String at = iso(r.watchedAt);
            if (!e.isEpisode()) {
                movies.put(new JSONObject().put("ids", ids).put("watched_at", at));
                continue;
            }
            String showKey = ids.toString();
            shows.put(showKey, ids);
            Map<Integer, JSONArray> bySeason = seasons.computeIfAbsent(showKey, k -> new HashMap<>());
            bySeason.computeIfAbsent(e.season, k -> new JSONArray())
                    .put(new JSONObject().put("number", e.episode).put("watched_at", at));
        }
        JSONObject body = new JSONObject();
        if (movies.length() > 0) body.put("movies", movies);
        if (!shows.isEmpty()) {
            JSONArray list = new JSONArray();
            for (Map.Entry<String, JSONObject> s : shows.entrySet()) {
                JSONArray seasonList = new JSONArray();
                for (Map.Entry<Integer, JSONArray> season : seasons.get(s.getKey()).entrySet()) {
                    seasonList.put(new JSONObject().put("number", season.getKey())
                            .put("episodes", season.getValue()));
                }
                list.put(new JSONObject().put("ids", s.getValue()).put("seasons", seasonList));
            }
            body.put("shows", list);
        }
        return body;
    }

    private static long timestamp(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
                Date d = f.parse(iso);
                if (d != null) return d.getTime();
            } catch (java.text.ParseException ignored) {
                // Try the next.
            }
        }
        return 0;
    }

    private static String iso(long epochMs) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMs > 0 ? epochMs : System.currentTimeMillis()));
    }
}
