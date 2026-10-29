package com.shiggsy365.bingetvredux;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a profile has watched, and where playback stopped: one row per video, keyed by its
 * Stremio id ("tt0111161" for a film, "tt0903747:1:2" for an episode). The player writes it;
 * badges, Continue Watching, Next Up and the info page read it; MDBList sync merges into it.
 * Rows are kept in memory too, so screens can read them on the main thread.
 */
public final class WatchStore {
    // Too short to be worth a resume point.
    private static final long MIN_RESUME_MS = 120_000;

    public static final int NONE = 0, RESUME = 1, WATCHED = 2;

    /** What's playing, as watch state and MDBList need to know it. */
    public static final class Entry {
        public final String videoId;
        public final String type;
        /** The film's id, or the show's for an episode. */
        public final String metaId;
        /** -1 for films. */
        public final int season;
        public final int episode;
        /** The episode's title (or the film's). */
        public final String title;
        /** The film's or show's name. */
        public final String name;
        public final String poster;
        public final String background;
        public final String logo;
        public final String bingeGroup;
        /** External ids of the film or show ("imdb", "tmdb"…), for MDBList. */
        public final JSONObject ids;

        public Entry(String videoId, String type, String metaId, int season, int episode,
                String title, String name, String poster, String background, String logo,
                String bingeGroup, JSONObject ids) {
            this.videoId = videoId;
            this.type = type;
            this.metaId = metaId;
            this.season = season;
            this.episode = episode;
            this.title = title == null ? "" : title;
            this.name = name == null ? "" : name;
            this.poster = poster == null ? "" : poster;
            this.background = background == null ? "" : background;
            this.logo = logo == null ? "" : logo;
            this.bingeGroup = bingeGroup == null ? "" : bingeGroup;
            this.ids = ids == null ? new JSONObject() : ids;
        }

        public boolean isEpisode() {
            return season >= 0 && episode >= 0;
        }
    }

    /** A stored row. */
    public static final class Row {
        public Entry entry;
        public long positionMs;
        public long durationMs;
        public boolean watched;
        public long watchedAt;
        public long updatedAt;
        public boolean synced;

        public float progress() {
            return durationMs > 0 ? Math.min(1f, positionMs / (float) durationMs) : 0f;
        }
    }

    private static WatchStore sInstance;
    private final Helper mHelper;
    private final Map<String, Row> mRows = new HashMap<>();
    // Shows hidden from Next Up and Continue Watching until something newer happens in them.
    private final Map<String, Long> mDismissed = new HashMap<>();
    private final List<Runnable> mListeners = new ArrayList<>();
    // Show or film id -> its most recent part-watched row; null until needed after a change.
    private Map<String, Row> mResumeByMeta;
    private int mVersion;

    private WatchStore(Context context, String profileId) {
        mHelper = new Helper(context.getApplicationContext(), dbName(profileId));
        SQLiteDatabase db = mHelper.getReadableDatabase();
        try (Cursor c = db.query("progress", null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                Row r = read(c);
                mRows.put(r.entry.videoId, r);
            }
        }
        try (Cursor c = db.query("dismissed", null, null, null, null, null, null)) {
            while (c.moveToNext()) mDismissed.put(c.getString(0), c.getLong(1));
        }
    }

    /** The current profile's store. */
    public static synchronized WatchStore get(Context context) {
        if (sInstance == null) sInstance = new WatchStore(context, Profiles.current(context).id);
        return sInstance;
    }

    /** Forgets the open store, e.g. after switching profile. */
    static synchronized void reset() {
        if (sInstance != null) sInstance.mHelper.close();
        sInstance = null;
    }

    static synchronized void delete(Context context, String profileId) {
        reset();
        context.deleteDatabase(dbName(profileId));
    }

    private static String dbName(String profileId) {
        return "watch_" + profileId + ".db";
    }

    /** Called (on whatever thread made the change) whenever anything changes. */
    public synchronized void addListener(Runnable listener) {
        mListeners.add(listener);
    }

    public synchronized void removeListener(Runnable listener) {
        mListeners.remove(listener);
    }

    private void changed() {
        synchronized (this) {
            mVersion++;
        }
        for (Runnable r : new ArrayList<>(mListeners)) r.run();
    }

    /** Goes up with every change, so screens can tell they're out of date. */
    public synchronized int version() {
        return mVersion;
    }

    // ---- Reading ----

    public synchronized Row row(String videoId) {
        return mRows.get(videoId);
    }

    public synchronized boolean isWatched(String videoId) {
        Row r = mRows.get(videoId);
        return r != null && r.watched;
    }

    /** Part-watched films and episodes, last played first, one episode per show. */
    public synchronized List<Row> continueWatching(int limit) {
        List<Row> rows = new ArrayList<>();
        for (Row r : mRows.values()) if (r.positionMs > 0 && !r.watched) rows.add(r);
        Collections.sort(rows, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        List<Row> out = new ArrayList<>();
        Set<String> shows = new HashSet<>();
        for (Row r : rows) {
            if (r.entry.isEpisode()) {
                if (!shows.add(r.entry.metaId)) continue;
                Long dismissed = mDismissed.get(r.entry.metaId);
                if (dismissed != null && r.updatedAt <= dismissed) continue;
            }
            out.add(r);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** The part-watched row of a film, or the show's most recent part-watched episode. */
    public synchronized Row resumeFor(String metaId) {
        if (mResumeByMeta == null) {
            // Built when first needed after a change: posters ask this for every card.
            mResumeByMeta = new HashMap<>();
            for (Row r : mRows.values()) {
                if (r.positionMs <= 0 || r.watched || r.entry.metaId.isEmpty()) continue;
                Row best = mResumeByMeta.get(r.entry.metaId);
                if (best == null || r.updatedAt > best.updatedAt) mResumeByMeta.put(r.entry.metaId, r);
            }
        }
        return mResumeByMeta.get(metaId);
    }

    /** Shows with watched episodes, most recently active first: {show id, type}. */
    public synchronized List<String[]> recentShows(int limit) {
        Set<String> withWatched = new HashSet<>();
        for (Row r : mRows.values()) {
            if (r.watched && r.entry.isEpisode() && !r.entry.metaId.isEmpty()) {
                withWatched.add(r.entry.metaId);
            }
        }
        Map<String, Long> active = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        for (Row r : mRows.values()) {
            if (!r.entry.isEpisode() || !withWatched.contains(r.entry.metaId)) continue;
            long t = Math.max(r.updatedAt, r.watchedAt);
            Long prev = active.get(r.entry.metaId);
            if (prev == null || t > prev) active.put(r.entry.metaId, t);
            types.put(r.entry.metaId, r.entry.type);
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(active.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : list) {
            Long dismissed = mDismissed.get(e.getKey());
            if (dismissed != null && e.getValue() <= dismissed) continue;
            out.add(new String[]{e.getKey(), types.get(e.getKey())});
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** "season:episode" of the show's watched episodes. */
    public synchronized Set<String> watchedEpisodes(String metaId) {
        Set<String> out = new HashSet<>();
        for (Row r : mRows.values()) {
            if (r.watched && r.entry.isEpisode() && metaId.equals(r.entry.metaId)) {
                out.add(r.entry.season + ":" + r.entry.episode);
            }
        }
        return out;
    }

    /** The source group last used for a show, so the next episode can come from it too. */
    public synchronized String bingeGroup(String metaId, long maxAgeMs) {
        Row best = null;
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        for (Row r : mRows.values()) {
            if (!metaId.equals(r.entry.metaId) || r.entry.bingeGroup.isEmpty()
                    || r.updatedAt < cutoff) {
                continue;
            }
            if (best == null || r.updatedAt > best.updatedAt) best = r;
        }
        return best == null ? "" : best.entry.bingeGroup;
    }

    public synchronized List<Row> unsyncedWatched() {
        List<Row> out = new ArrayList<>();
        for (Row r : mRows.values()) if (r.watched && !r.synced) out.add(r);
        return out;
    }

    public synchronized List<Row> all() {
        return new ArrayList<>(mRows.values());
    }

    // ---- Writing ----

    /**
     * Stores playback progress. Returns WATCHED, RESUME or NONE (too short to count; an
     * earlier resume point is kept).
     */
    public int record(Entry entry, long positionMs, long durationMs, int watchedPercent) {
        if (durationMs <= 0) return NONE;
        int status;
        synchronized (this) {
            long now = System.currentTimeMillis();
            Row row = merged(entry);
            if (positionMs >= durationMs * watchedPercent / 100) {
                if (!row.watched) row.synced = false;
                row.watched = true;
                row.watchedAt = now;
                row.positionMs = 0;
                status = WATCHED;
            } else if (positionMs >= MIN_RESUME_MS) {
                row.positionMs = positionMs;
                // Watching again: no longer counts as watched until it's finished.
                row.watched = false;
                status = RESUME;
            } else {
                return NONE;
            }
            row.durationMs = durationMs;
            row.updatedAt = now;
            write(row);
        }
        changed();
        return status;
    }

    /** Remembers what's being played (e.g. its source group) without progress. */
    public void touch(Entry entry) {
        synchronized (this) {
            Row row = merged(entry);
            row.updatedAt = System.currentTimeMillis();
            write(row);
        }
        changed();
    }

    public void setWatched(List<Entry> entries, boolean watched) {
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Entry entry : entries) {
                Row row = merged(entry);
                if (row.watched == watched && row.positionMs == 0) continue;
                row.watched = watched;
                row.positionMs = 0;
                row.watchedAt = watched ? now : 0;
                row.synced = false;
                if (watched) row.updatedAt = now;
                write(row);
            }
        }
        changed();
    }

    /** Takes an item off Continue Watching; an episode's show leaves Next Up too. */
    public void clearResume(String videoId) {
        synchronized (this) {
            Row row = mRows.get(videoId);
            if (row == null) return;
            long now = System.currentTimeMillis();
            row.positionMs = 0;
            row.updatedAt = now;
            write(row);
            if (row.entry.isEpisode()) dismissShowLocked(row.entry.metaId, now);
        }
        changed();
    }

    public void dismissShow(String metaId) {
        synchronized (this) {
            dismissShowLocked(metaId, System.currentTimeMillis());
        }
        changed();
    }

    private void dismissShowLocked(String metaId, long when) {
        mDismissed.put(metaId, when);
        ContentValues v = new ContentValues();
        v.put("meta_id", metaId);
        v.put("dismissed_at", when);
        mHelper.getWritableDatabase().insertWithOnConflict("dismissed", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void markSynced(List<String> videoIds) {
        for (String id : videoIds) {
            Row row = mRows.get(id);
            if (row != null && !row.synced) {
                row.synced = true;
                write(row);
            }
        }
    }

    /**
     * Applies a watched list from elsewhere (MDBList): each becomes watched and synced.
     * Returns how many were newly watched.
     */
    public int mergeRemote(List<Entry> entries, List<Long> watchedAt) {
        int added = 0;
        synchronized (this) {
            for (int i = 0; i < entries.size(); i++) {
                Row row = merged(entries.get(i));
                if (row.watched && row.synced) continue;
                if (!row.watched) added++;
                row.watched = true;
                row.synced = true;
                row.positionMs = 0;
                long at = watchedAt.get(i);
                row.watchedAt = at > 0 ? at : (row.watchedAt > 0 ? row.watchedAt : System.currentTimeMillis());
                row.updatedAt = Math.max(row.updatedAt, row.watchedAt);
                write(row);
            }
        }
        if (added > 0) changed();
        return added;
    }

    public void clear() {
        synchronized (this) {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            db.delete("progress", null, null);
            db.delete("dismissed", null, null);
            mRows.clear();
            mDismissed.clear();
            mResumeByMeta = null;
        }
        changed();
    }

    /** The stored row with the entry's details applied (a new one if there's none). */
    private Row merged(Entry entry) {
        Row row = mRows.get(entry.videoId);
        if (row == null) {
            row = new Row();
            row.entry = entry;
            return row;
        }
        Entry old = row.entry;
        JSONObject ids = old.ids;
        try {
            ids = new JSONObject(old.ids.toString());
            for (java.util.Iterator<String> it = entry.ids.keys(); it.hasNext(); ) {
                String k = it.next();
                ids.put(k, entry.ids.get(k));
            }
        } catch (JSONException ignored) {
            // Keeps the old ids.
        }
        row.entry = new Entry(entry.videoId, pick(entry.type, old.type), pick(entry.metaId, old.metaId),
                entry.season >= 0 ? entry.season : old.season,
                entry.episode >= 0 ? entry.episode : old.episode,
                pick(entry.title, old.title), pick(entry.name, old.name),
                pick(entry.poster, old.poster), pick(entry.background, old.background),
                pick(entry.logo, old.logo), pick(entry.bingeGroup, old.bingeGroup), ids);
        return row;
    }

    private static String pick(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private void write(Row row) {
        mRows.put(row.entry.videoId, row);
        mResumeByMeta = null;
        Entry e = row.entry;
        ContentValues v = new ContentValues();
        v.put("video_id", e.videoId);
        v.put("type", e.type);
        v.put("meta_id", e.metaId);
        v.put("season", e.season);
        v.put("episode", e.episode);
        v.put("title", e.title);
        v.put("name", e.name);
        v.put("poster", e.poster);
        v.put("background", e.background);
        v.put("logo", e.logo);
        v.put("binge_group", e.bingeGroup);
        v.put("ids", e.ids.toString());
        v.put("position_ms", row.positionMs);
        v.put("duration_ms", row.durationMs);
        v.put("watched", row.watched ? 1 : 0);
        v.put("watched_at", row.watchedAt);
        v.put("updated_at", row.updatedAt);
        v.put("synced", row.synced ? 1 : 0);
        mHelper.getWritableDatabase().insertWithOnConflict("progress", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static Row read(Cursor c) {
        JSONObject ids;
        try {
            ids = new JSONObject(c.getString(c.getColumnIndexOrThrow("ids")));
        } catch (JSONException e) {
            ids = new JSONObject();
        }
        Row r = new Row();
        r.entry = new Entry(str(c, "video_id"), str(c, "type"), str(c, "meta_id"),
                c.getInt(c.getColumnIndexOrThrow("season")),
                c.getInt(c.getColumnIndexOrThrow("episode")), str(c, "title"), str(c, "name"),
                str(c, "poster"), str(c, "background"), str(c, "logo"), str(c, "binge_group"), ids);
        r.positionMs = c.getLong(c.getColumnIndexOrThrow("position_ms"));
        r.durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
        r.watched = c.getInt(c.getColumnIndexOrThrow("watched")) != 0;
        r.watchedAt = c.getLong(c.getColumnIndexOrThrow("watched_at"));
        r.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        r.synced = c.getInt(c.getColumnIndexOrThrow("synced")) != 0;
        return r;
    }

    private static String str(Cursor c, String column) {
        String s = c.getString(c.getColumnIndexOrThrow(column));
        return s == null ? "" : s;
    }

    /** For backups: a profile's rows. */
    static JSONArray export(Context context, String profileId) throws JSONException {
        WatchStore store = profileId.equals(Profiles.current(context).id) ? get(context)
                : new WatchStore(context, profileId);
        JSONArray out = new JSONArray();
        synchronized (store) {
            for (Row r : store.mRows.values()) {
                WatchStore.Entry e = r.entry;
                out.put(new JSONObject().put("videoId", e.videoId).put("type", e.type)
                        .put("metaId", e.metaId).put("season", e.season).put("episode", e.episode)
                        .put("title", e.title).put("name", e.name).put("poster", e.poster)
                        .put("background", e.background).put("logo", e.logo)
                        .put("bingeGroup", e.bingeGroup).put("ids", e.ids)
                        .put("position", r.positionMs).put("duration", r.durationMs)
                        .put("watched", r.watched).put("watchedAt", r.watchedAt)
                        .put("updatedAt", r.updatedAt).put("synced", r.synced));
            }
        }
        if (store != sInstance) store.mHelper.close();
        return out;
    }

    /** From a backup: replaces a profile's rows. */
    static void restore(Context context, String profileId, JSONArray rows) throws JSONException {
        reset();
        context.deleteDatabase(dbName(profileId));
        WatchStore store = new WatchStore(context, profileId);
        SQLiteDatabase db = store.mHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.getJSONObject(i);
                Row r = new Row();
                r.entry = new Entry(o.getString("videoId"), o.optString("type"), o.optString("metaId"),
                        o.optInt("season", -1), o.optInt("episode", -1), o.optString("title"),
                        o.optString("name"), o.optString("poster"), o.optString("background"),
                        o.optString("logo"), o.optString("bingeGroup"), o.optJSONObject("ids"));
                r.positionMs = o.optLong("position");
                r.durationMs = o.optLong("duration");
                r.watched = o.optBoolean("watched");
                r.watchedAt = o.optLong("watchedAt");
                r.updatedAt = o.optLong("updatedAt");
                r.synced = o.optBoolean("synced");
                store.write(r);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            store.mHelper.close();
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context, String name) {
            super(context, name, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE progress (video_id TEXT PRIMARY KEY, type TEXT, meta_id TEXT,"
                    + " season INTEGER, episode INTEGER, title TEXT, name TEXT, poster TEXT,"
                    + " background TEXT, logo TEXT, binge_group TEXT, ids TEXT,"
                    + " position_ms INTEGER, duration_ms INTEGER, watched INTEGER,"
                    + " watched_at INTEGER, updated_at INTEGER, synced INTEGER)");
            db.execSQL("CREATE INDEX progress_meta ON progress (meta_id)");
            db.execSQL("CREATE TABLE dismissed (meta_id TEXT PRIMARY KEY, dismissed_at INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the first.
        }
    }
}
