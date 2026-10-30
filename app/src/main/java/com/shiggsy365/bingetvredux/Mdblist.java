package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.RequestLog;

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

    private static final long ITEM_TTL_MS = 12 * 3600_000L;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    // Fetching the watchlist and syncing each have their own lock, so neither waits for the
    // other, and screens reading the cached watchlist never wait for either.
    private static final Object WATCHLIST_LOCK = new Object();
    private static final Object SYNC_LOCK = new Object();
    private static volatile List<MetaItem> sWatchlist;
    private static volatile long sWatchlistAt;
    private static volatile String sWatchlistKey = "";
    private static final Map<String, Object[]> sItems = new HashMap<>();

    static {
        Stremio.logRequests();
    }

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
        String address = url.build().toString();
        long started = System.currentTimeMillis();
        String result = "error";
        long bytes = 0;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
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
            result = String.valueOf(status);
            String type = conn.getContentType() == null ? "" : conn.getContentType();
            if ((status == 401 || status == 403) && type.contains("json")) {
                throw new MdblistException("MDBList rejected the API key");
            }
            if (status >= 400) throw new MdblistException("MDBList answered HTTP " + status);
            String text = read(conn.getInputStream());
            bytes = text.length();
            if (text.isEmpty()) return new JSONObject();
            return text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        } catch (IOException e) {
            throw new MdblistException("Couldn't reach MDBList: " + e.getMessage());
        } catch (JSONException e) {
            throw new MdblistException("MDBList's answer isn't JSON");
        } finally {
            if (conn != null) conn.disconnect();
            RequestLog.log(method, address, result, bytes, started);
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

    private interface PageHandler {
        void take(JSONObject page) throws MdblistException, JSONException;
    }

    /** Hands over each page of a paged endpoint in turn, so only one is held at a time. */
    private static void forEachPage(String key, String path, Map<String, String> params,
            PageHandler handler) throws MdblistException, JSONException {
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            Map<String, String> p = new HashMap<>(params);
            p.put("limit", "1000");
            if (cursor != null) p.put("cursor", cursor);
            Object answer = request(key, "GET", path, p, null);
            if (!(answer instanceof JSONObject)) break;
            JSONObject data = (JSONObject) answer;
            handler.take(data);
            JSONObject pagination = data.optJSONObject("pagination");
            cursor = pagination == null ? null : pagination.optString("next_cursor", null);
            if (cursor == null || cursor.isEmpty() || "null".equals(cursor)) break;
        }
    }

    /** Every page of a paged endpoint, its lists merged (for short lists such as the watchlist). */
    private static JSONObject paged(String key, String path, Map<String, String> params)
            throws MdblistException, JSONException {
        JSONObject merged = new JSONObject();
        forEachPage(key, path, params, data -> {
            for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray list = data.optJSONArray(k);
                if (list == null) continue;
                JSONArray into = merged.optJSONArray(k);
                if (into == null) merged.put(k, into = new JSONArray());
                for (int i = 0; i < list.length(); i++) into.put(list.get(i));
            }
        });
        return merged;
    }

    // ---- Ids ----

    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d+");

    private static JSONObject supported(JSONObject ids, boolean show) throws JSONException {
        JSONObject out = new JSONObject();
        for (String k : show ? SHOW_IDS : MOVIE_IDS) {
            Object v = ids.opt(k);
            if (v == null || v == JSONObject.NULL || String.valueOf(v).isEmpty()) continue;
            String s = String.valueOf(v);
            // MDBList wants numbers as numbers.
            out.put(k, k.equals("imdb") || !DIGITS.matcher(s).matches() ? s : Long.parseLong(s));
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
    public static List<MetaItem> watchlist(Context context, boolean refresh)
            throws MdblistException {
        synchronized (WATCHLIST_LOCK) {
            return watchlistLocked(context, refresh);
        }
    }

    private static List<MetaItem> watchlistLocked(Context context, boolean refresh)
            throws MdblistException {
        String key = key(context);
        if (key.isEmpty()) return new ArrayList<>();
        List<MetaItem> cached = sWatchlist;
        if (!refresh && cached != null && key.equals(sWatchlistKey)
                && System.currentTimeMillis() - sWatchlistAt < WATCHLIST_TTL_MS) {
            return cached;
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
        sWatchlistKey = key;
        sWatchlistAt = System.currentTimeMillis();
        sWatchlist = items;
        return items;
    }

    /** The cached watchlist without asking MDBList; null if it hasn't been loaded. */
    public static List<MetaItem> cachedWatchlist() {
        return sWatchlist;
    }

    /** Whether a title is on the (cached) watchlist; false if it hasn't been loaded. */
    public static boolean onWatchlist(String id) {
        List<MetaItem> list = sWatchlist;
        if (list == null) return false;
        for (MetaItem m : list) if (m.id.equals(id)) return true;
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
        synchronized (WATCHLIST_LOCK) {
            if (sWatchlist != null) {
                List<MetaItem> list = new ArrayList<>(sWatchlist);
                list.removeIf(m -> m.id.equals(item.id));
                if (add) list.add(0, item);
                sWatchlist = list;
            }
        }
    }

    // ---- Title lookups ----

    /** What MDBList knows about a title, for its ratings and similar titles. */
    public static final class Item {
        /** 0–100 averaged over MDBList's score and each rating source; 0 if none. */
        public final int score;
        public final int sources;
        public final List<MetaItem> similar;

        Item(int score, int sources, List<MetaItem> similar) {
            this.score = score;
            this.sources = sources;
            this.similar = similar;
        }
    }

    private static final Map<String, Integer> RATING_SCALES = new HashMap<>();
    static {
        RATING_SCALES.put("imdb", 10);
        RATING_SCALES.put("tomatoes", 100);
        RATING_SCALES.put("popcorn", 100);
        RATING_SCALES.put("metacritic", 100);
        RATING_SCALES.put("trakt", 100);
        RATING_SCALES.put("tmdb", 100);
        RATING_SCALES.put("letterboxd", 5);
        RATING_SCALES.put("rogerebert", 4);
        RATING_SCALES.put("myanimelist", 10);
    }

    /** "/imdb/movie/tt…" (or tmdb/tvdb) for a Stremio id; null if MDBList can't look it up. */
    static String lookupPath(String type, String id) {
        String media = "movie".equals(type) ? "movie" : "show";
        String base = id.startsWith("tt") ? id.split(":")[0] : id;
        if (base.startsWith("tt")) return "/imdb/" + media + "/" + base;
        for (String provider : new String[]{"tmdb", "tvdb"}) {
            if (base.startsWith(provider + ":")) return "/" + provider + "/" + media + "/" + base.split(":")[1];
        }
        return null;
    }

    /** A title's ratings and similar titles, kept for 12 hours; null without a key or id. */
    public static Item item(Context context, String type, String id) throws MdblistException {
        String key = key(context);
        String path = lookupPath(type, id);
        if (key.isEmpty() || path == null) return null;
        synchronized (sItems) {
            Object[] cached = sItems.get(path);
            if (cached != null && System.currentTimeMillis() - (Long) cached[0] < ITEM_TTL_MS) {
                return (Item) cached[1];
            }
        }
        Map<String, String> params = new HashMap<>();
        params.put("append_to_response", "recommendations");
        Object answer = request(key, "GET", path, params, null);
        Item item;
        try {
            item = parseItem(answer instanceof JSONObject ? (JSONObject) answer : new JSONObject());
        } catch (JSONException e) {
            throw new MdblistException("MDBList's answer couldn't be read");
        }
        synchronized (sItems) {
            sItems.put(path, new Object[]{System.currentTimeMillis(), item});
        }
        return item;
    }

    static Item parseItem(JSONObject data) throws JSONException {
        double total = 0;
        int count = 0;
        double score = data.optDouble("score", 0);
        if (score > 0) {
            total += score;
            count++;
        }
        JSONArray ratings = data.optJSONArray("ratings");
        for (int i = 0; ratings != null && i < ratings.length(); i++) {
            JSONObject r = ratings.optJSONObject(i);
            if (r == null) continue;
            Integer scale = RATING_SCALES.get(r.optString("source"));
            double value = r.optDouble("value", 0);
            if (scale != null && value > 0) {
                total += Math.min(100, value * 100 / scale);
                count++;
            }
        }
        List<MetaItem> similar = new ArrayList<>();
        JSONArray recs = data.optJSONArray("recommendations");
        for (int i = 0; recs != null && i < recs.length(); i++) {
            JSONObject rec = recs.optJSONObject(i);
            if (rec == null || rec.optString("title").isEmpty()) continue;
            boolean show = "show".equals(rec.optString("mediatype"));
            JSONObject ids = rec.optJSONObject("ids") == null ? new JSONObject() : rec.getJSONObject("ids");
            String sid = stremioId(supported(ids, show));
            if (sid == null) continue;
            MetaItem m = MetaItem.preview(new JSONObject().put("id", sid)
                    .put("type", show ? "series" : "movie").put("name", rec.optString("title"))
                    .put("poster", rec.optString("poster", ""))
                    .put("releaseInfo", rec.has("release_year") ? String.valueOf(rec.opt("release_year")) : "")
                    .put("description", rec.optString("description", "")), null);
            if (m != null) similar.add(m);
        }
        return new Item(count > 0 ? (int) Math.round(total / count) : 0, count, similar);
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
     * Pushes watched items MDBList hasn't had, pulls its watched history if it has changed (or
     * {@code force}), takes resume points from other devices and, when forced, refreshes the
     * watchlist. Returns a summary for the web page.
     */
    public static String sync(Context context, boolean force) throws MdblistException {
        synchronized (SYNC_LOCK) {
            return syncLocked(context, force);
        }
    }

    private static String syncLocked(Context context, boolean force) throws MdblistException {
        String key = key(context);
        if (key.isEmpty()) throw new MdblistException("Add an MDBList API key first");
        WatchStore store = WatchStore.get(context);
        int pushed = 0, added = 0, resumes = 0, listed = -1;
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
            // Resume points from other devices: cheap, and not covered by the activity markers.
            resumes = pullResumes(key, store, Settings.integer(context, Settings.WATCHED_PERCENT));
        } catch (JSONException e) {
            throw new MdblistException("MDBList's answer couldn't be read");
        }
        if (force) listed = watchlist(context, true).size();
        return "Sent " + pushed + " watched, received " + added + " new"
                + (resumes > 0 ? ", " + resumes + " resume points" : "")
                + (listed >= 0 ? "; " + listed + " on the watchlist" : "");
    }

    /**
     * Merges the watched history a page at a time; returns how many were newly watched. Pages
     * are read as a stream, keeping only what's needed: building a full JSON tree of each page
     * (thousands of items) took longer on a Fire TV Stick than downloading it.
     */
    private static int pull(String key, WatchStore store) throws MdblistException {
        int added = 0;
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            Map<String, String> params = new HashMap<>();
            params.put("limit", "1000");
            if (cursor != null) params.put("cursor", cursor);
            WatchedPage data = new WatchedPage(store);
            stream(key, "/sync/watched", params, data::read);
            added += store.mergeRemote(data.entries, data.times);
            cursor = data.nextCursor;
            if (cursor == null || cursor.isEmpty()) break;
        }
        return added;
    }

    private interface StreamHandler {
        void read(JsonReader reader) throws IOException;
    }

    /** A GET whose answer is read as a stream by {@code handler}. */
    private static void stream(String key, String path, Map<String, String> params,
            StreamHandler handler) throws MdblistException {
        Uri.Builder url = Uri.parse(BASE + path).buildUpon().appendQueryParameter("apikey", key);
        for (Map.Entry<String, String> e : params.entrySet()) url.appendQueryParameter(e.getKey(), e.getValue());
        String address = url.build().toString();
        long started = System.currentTimeMillis();
        String result = "error";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            result = String.valueOf(status);
            String type = conn.getContentType() == null ? "" : conn.getContentType();
            if ((status == 401 || status == 403) && type.contains("json")) {
                throw new MdblistException("MDBList rejected the API key");
            }
            if (status >= 400) throw new MdblistException("MDBList answered HTTP " + status);
            try (JsonReader reader = new JsonReader(new java.io.InputStreamReader(
                    new java.io.BufferedInputStream(conn.getInputStream()), StandardCharsets.UTF_8))) {
                handler.read(reader);
            }
        } catch (IOException | IllegalStateException e) {
            throw new MdblistException("Couldn't read MDBList's answer: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
            RequestLog.log("GET", address, result, -1, started);
        }
    }

    /** One page of /sync/watched: its movies and episodes as watch entries. */
    private static final class WatchedPage {
        final WatchStore store;
        final List<WatchStore.Entry> entries = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        String nextCursor;

        WatchedPage(WatchStore store) {
            this.store = store;
        }

        void read(JsonReader r) throws IOException {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if ((name.equals("movies") || name.equals("episodes")) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) item(r, name.equals("episodes"));
                    r.endArray();
                } else if (name.equals("pagination") && r.peek() == JsonToken.BEGIN_OBJECT) {
                    r.beginObject();
                    while (r.hasNext()) {
                        if (r.nextName().equals("next_cursor") && r.peek() == JsonToken.STRING) {
                            nextCursor = r.nextString();
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }

        /** {"last_watched_at", "movie": {title, ids}} or {"last_watched_at", "episode": {season, number, title, show: {title, ids}}}. */
        private void item(JsonReader r, boolean episode) throws IOException {
            String at = "", title = "", showTitle = "";
            int season = -1, number = -1;
            JSONObject ids = null;
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (name.equals("last_watched_at") && r.peek() == JsonToken.STRING) {
                    at = r.nextString();
                } else if (name.equals(episode ? "episode" : "movie") && r.peek() == JsonToken.BEGIN_OBJECT) {
                    r.beginObject();
                    while (r.hasNext()) {
                        String field = r.nextName();
                        if (field.equals("title") && r.peek() == JsonToken.STRING) {
                            title = r.nextString();
                        } else if (!episode && field.equals("ids") && r.peek() == JsonToken.BEGIN_OBJECT) {
                            ids = readIds(r);
                        } else if (episode && field.equals("season") && r.peek() == JsonToken.NUMBER) {
                            season = r.nextInt();
                        } else if (episode && field.equals("number") && r.peek() == JsonToken.NUMBER) {
                            number = r.nextInt();
                        } else if (episode && field.equals("show") && r.peek() == JsonToken.BEGIN_OBJECT) {
                            r.beginObject();
                            while (r.hasNext()) {
                                String part = r.nextName();
                                if (part.equals("title") && r.peek() == JsonToken.STRING) showTitle = r.nextString();
                                else if (part.equals("ids") && r.peek() == JsonToken.BEGIN_OBJECT) ids = readIds(r);
                                else r.skipValue();
                            }
                            r.endObject();
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            if (ids == null) return;
            // Most of a long history is already here: skip those before any more work.
            String known = quickId(ids, episode);
            if (known != null && store.isSyncedWatched(episode ? known + ":" + season + ":" + number : known)) {
                return;
            }
            try {
                JSONObject usable = supported(ids, episode);
                String id = stremioId(usable);
                if (id == null) return;
                if (episode) {
                    if (season < 0 || number < 0) return;
                    entries.add(new WatchStore.Entry(id + ":" + season + ":" + number, "series", id,
                            season, number, title, showTitle, "", "", "", "", usable));
                } else {
                    entries.add(new WatchStore.Entry(id, "movie", id, -1, -1, title, title, "", "",
                            "", "", usable));
                }
                times.add(timestamp(at));
            } catch (JSONException ignored) {
                // Skips the item.
            }
        }

        /** The id stremioId(supported(ids)) gives, worked out cheaply; null if there's none. */
        private static String quickId(JSONObject ids, boolean show) {
            String imdb = ids.optString("imdb", "");
            if (!imdb.isEmpty() && !"null".equals(imdb)) return imdb;
            String tmdb = ids.optString("tmdb", "");
            if (!tmdb.isEmpty() && !"null".equals(tmdb)) return "tmdb:" + tmdb;
            String tvdb = show ? ids.optString("tvdb", "") : "";
            return !tvdb.isEmpty() && !"null".equals(tvdb) ? "tvdb:" + tvdb : null;
        }

        private static JSONObject readIds(JsonReader r) throws IOException {
            JSONObject ids = new JSONObject();
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                JsonToken token = r.peek();
                try {
                    if (token == JsonToken.STRING || token == JsonToken.NUMBER) ids.put(name, r.nextString());
                    else r.skipValue();
                } catch (JSONException e) {
                    // Leaves this id out.
                }
            }
            r.endObject();
            return ids;
        }
    }

    /** MDBList's paused sessions as resume points; returns how many were applied. */
    private static int pullResumes(String key, WatchStore store, int watchedPercent)
            throws MdblistException, JSONException {
        Object answer = request(key, "GET", "/sync/playback", null, null);
        JSONArray sessions = answer instanceof JSONArray ? (JSONArray) answer
                : ((JSONObject) answer).optJSONArray("playback");
        List<WatchStore.Entry> entries = new ArrayList<>();
        List<long[]> points = new ArrayList<>();
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s == null) continue;
            double progress = s.optDouble("progress", 0);
            long durationMs = s.optLong("runtime", 0) * 60_000L;
            if (progress <= 0 || durationMs <= 0) continue;
            long at = s.has("updated_at_ts") ? (long) (s.optDouble("updated_at_ts") * 1000)
                    : timestamp(s.optString("updated_at"));
            WatchStore.Entry entry;
            if ("episode".equals(s.optString("type"))) {
                JSONObject episode = s.optJSONObject("episode") == null ? new JSONObject() : s.getJSONObject("episode");
                JSONObject show = s.optJSONObject("show") != null ? s.getJSONObject("show")
                        : episode.optJSONObject("show") != null ? episode.getJSONObject("show") : new JSONObject();
                JSONObject ids = supported(show.optJSONObject("ids") == null ? new JSONObject()
                        : show.getJSONObject("ids"), true);
                String showId = stremioId(ids);
                int season = episode.optInt("season", -1);
                int number = episode.optInt("number", episode.optInt("episode", -1));
                if (showId == null || season < 0 || number < 0) continue;
                entry = new WatchStore.Entry(showId + ":" + season + ":" + number, "series", showId,
                        season, number, episode.optString("title"), show.optString("title"), "", "",
                        "", "", ids);
            } else {
                JSONObject movie = s.optJSONObject("movie") == null ? new JSONObject() : s.getJSONObject("movie");
                JSONObject ids = supported(movie.optJSONObject("ids") == null ? new JSONObject()
                        : movie.getJSONObject("ids"), false);
                String id = stremioId(ids);
                if (id == null) continue;
                entry = new WatchStore.Entry(id, "movie", id, -1, -1, movie.optString("title"),
                        movie.optString("title"), "", "", "", "", ids);
            }
            entries.add(entry);
            points.add(new long[]{(long) (progress / 100.0 * durationMs), durationMs, at});
        }
        return store.mergeResume(entries, points, watchedPercent);
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

    // Made once per thread: building one for each of thousands of history items was slow.
    private static final ThreadLocal<SimpleDateFormat[]> ISO_FORMATS = new ThreadLocal<SimpleDateFormat[]>() {
        @Override
        protected SimpleDateFormat[] initialValue() {
            return new SimpleDateFormat[]{
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT),
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.ROOT)};
        }
    };

    private static long timestamp(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        for (SimpleDateFormat f : ISO_FORMATS.get()) {
            try {
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
