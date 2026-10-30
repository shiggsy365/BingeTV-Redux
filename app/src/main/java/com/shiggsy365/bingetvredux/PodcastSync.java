package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Keeps a profile's podcasts in step with a gPodder-compatible server (oPodSync, Nextcloud's
 * gPodder Sync, gpodder.net) through its API v2: subscriptions both ways, and where each
 * episode was left ("play" episode actions), so other apps (AntennaPod and the like) carry on
 * where the TV stopped, and the other way round. Changes made here wait in a queue until the
 * next sync, so nothing is lost while the server can't be reached.
 */
final class PodcastSync {
    private static final String TAG = "PodcastSync";
    // Sync bookkeeping, with the profile but not in backups (it belongs to this device).
    private static final String STATE = "podcastSync";
    private static final long DUE_AFTER_MS = 10 * 60_000L;
    private static final long PUSH_DELAY_MS = 5_000;
    private static final Background.Tasks sTasks = new Background.Tasks(1);
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    // One sync at a time; separate from the queue's lock so queueing never waits on the network.
    private static final Object SYNC_LOCK = new Object();
    private static Context sPushContext;
    private static final Runnable sPushNow = () -> {
        Context context = sPushContext;
        if (context != null) sTasks.execute(() -> {
            try {
                sync(context, Profiles.current(context).id);
            } catch (IOException e) {
                Log.i(TAG, "Sync waits: " + e.getMessage());
            }
        });
    };

    private PodcastSync() {}

    static boolean configured(Context context, String profileId) {
        return !Settings.string(context, profileId, Settings.PODCAST_SYNC_URL).trim().isEmpty()
                && !Settings.string(context, profileId, Settings.PODCAST_SYNC_USER).trim().isEmpty();
    }

    // ---- Queueing changes

    private static synchronized JSONObject state(Context context, String profileId) {
        try {
            return new JSONObject(Settings.data(context, profileId, STATE));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static synchronized void saveState(Context context, String profileId, JSONObject state) {
        Settings.putData(context, profileId, STATE, state.toString());
    }

    static synchronized void subscriptionChanged(Context context, String profileId, String feedUrl, boolean added) {
        if (!configured(context, profileId)) return;
        JSONObject state = state(context, profileId);
        try {
            JSONArray to = list(state, added ? "pendingAdd" : "pendingRemove");
            JSONArray from = list(state, added ? "pendingRemove" : "pendingAdd");
            remove(from, feedUrl);
            remove(to, feedUrl);
            to.put(feedUrl);
            saveState(context, profileId, state);
        } catch (JSONException e) {
            return;
        }
        pushSoon(context);
    }

    /** Where an episode was left, for the server (the latest per episode is kept). */
    static synchronized void played(Context context, String profileId, AudioPlayer.Item item,
            long positionMs, long durationMs) {
        if (!configured(context, profileId) || item.feedUrl.isEmpty()) return;
        JSONObject state = state(context, profileId);
        try {
            JSONArray actions = list(state, "pendingActions");
            JSONArray kept = new JSONArray();
            for (int i = 0; i < actions.length(); i++) {
                JSONObject a = actions.getJSONObject(i);
                if (!a.optString("episode").equals(item.id)) kept.put(a);
            }
            long position = positionMs / 1000;
            long total = Math.max(position, durationMs / 1000);
            kept.put(new JSONObject().put("podcast", item.feedUrl).put("episode", item.id)
                    .put("action", "play").put("timestamp", timestamp(System.currentTimeMillis()))
                    .put("started", position).put("position", position).put("total", total));
            state.put("pendingActions", kept);
            saveState(context, profileId, state);
        } catch (JSONException e) {
            Log.w(TAG, "Queueing: " + e.getMessage());
        }
    }

    /** Sends what's queued in a few seconds (several changes go together). */
    static void pushSoon(Context context) {
        if (!configured(context, Profiles.current(context).id)) return;
        sPushContext = context.getApplicationContext();
        sMain.removeCallbacks(sPushNow);
        sMain.postDelayed(sPushNow, PUSH_DELAY_MS);
    }

    /** Syncs in the background if it's been a while (opening the Podcasts page). */
    static void syncIfDue(Context context, Runnable then) {
        Context app = context.getApplicationContext();
        String profile = Profiles.current(app).id;
        if (!configured(app, profile)) return;
        if (System.currentTimeMillis() - state(app, profile).optLong("lastSync") < DUE_AFTER_MS) return;
        sTasks.execute(() -> {
            try {
                sync(app, profile);
                if (then != null) sMain.post(then);
            } catch (IOException e) {
                Log.i(TAG, "Sync failed: " + e.getMessage());
            }
        });
    }

    // ---- Syncing

    /** A whole sync for a profile; returns what happened, for the web page. Blocks. */
    static String sync(Context context, String profileId) throws IOException {
        synchronized (SYNC_LOCK) {
            return syncLocked(context, profileId);
        }
    }

    private static String syncLocked(Context context, String profileId) throws IOException {
        if (!configured(context, profileId)) throw new IOException("Enter the server, user name and password first");
        Server server = new Server(context, profileId);
        server.login();
        JSONObject state = state(context, profileId);
        String device = state.optString("device");
        try {
            // What's queued now is what this sync sends; anything queued meanwhile waits.
            JSONObject queued = new JSONObject(state.toString());
            if (device.isEmpty()) {
                device = "bingetv-redux-" + profileId.replaceAll("[^\\w.-]", "");
                Profiles.Profile p = Profiles.get(context, profileId);
                server.post("/api/2/devices/" + server.user + "/" + device + ".json", new JSONObject()
                        .put("caption", "BingeTV Redux" + (p != null ? " (" + p.name + ")" : ""))
                        .put("type", "other").toString());
                state.put("device", device);
            }
            int[] subs = syncSubscriptions(context, profileId, server, device, state);
            int[] eps = syncEpisodes(context, profileId, server, device, state);
            state.put("lastSync", System.currentTimeMillis());
            finish(context, profileId, state, queued);
            String message = "Synced: " + subs[0] + " podcasts added, " + subs[1] + " removed here; "
                    + eps[0] + " episode positions updated here, " + eps[1] + " sent.";
            Log.i(TAG, message);
            return message;
        } catch (JSONException e) {
            throw new IOException("The server's answer wasn't understood: " + e.getMessage());
        }
    }

    /** Saves the sync's progress, keeping changes queued while it ran. */
    private static synchronized void finish(Context context, String profileId, JSONObject synced,
            JSONObject queued) throws JSONException {
        JSONObject now = state(context, profileId);
        for (String key : new String[] {"pendingAdd", "pendingRemove"}) {
            JSONArray sent = list(queued, key), waiting = list(now, key);
            for (int i = 0; i < sent.length(); i++) remove(waiting, sent.getString(i));
        }
        JSONArray sent = list(queued, "pendingActions"), waiting = list(now, "pendingActions");
        Set<String> gone = new HashSet<>();
        for (int i = 0; i < sent.length(); i++) gone.add(sent.getJSONObject(i).toString());
        JSONArray kept = new JSONArray();
        for (int i = 0; i < waiting.length(); i++) {
            if (!gone.contains(waiting.getJSONObject(i).toString())) kept.put(waiting.getJSONObject(i));
        }
        now.put("pendingActions", kept);
        for (String key : new String[] {"device", "subsSince", "actionsSince", "lastSync"}) {
            now.put(key, synced.opt(key));
        }
        saveState(context, profileId, now);
    }

    /** Returns {added here, removed here}. */
    private static int[] syncSubscriptions(Context context, String profileId, Server server, String device,
            JSONObject state) throws IOException, JSONException {
        String path = "/api/2/subscriptions/" + server.user + "/" + device + ".json";
        long since = state.optLong("subsSince", 0);
        JSONObject changes = new JSONObject(server.get(path + "?since=" + since));
        List<Podcasts.Podcast> mine = Podcasts.subscriptions(context, profileId);
        Set<String> have = new HashSet<>();
        for (Podcasts.Podcast p : mine) have.add(p.feedUrl);
        JSONArray pendingAdd = list(state, "pendingAdd"), pendingRemove = list(state, "pendingRemove");
        int added = 0, removed = 0;
        JSONArray adds = changes.optJSONArray("add");
        for (int i = 0; adds != null && i < adds.length(); i++) {
            String url = adds.getString(i);
            if (have.contains(url) || contains(pendingRemove, url)) continue;
            mine.add(describe(url));
            have.add(url);
            added++;
        }
        JSONArray removes = changes.optJSONArray("remove");
        for (int i = 0; removes != null && i < removes.length(); i++) {
            String url = removes.getString(i);
            if (contains(pendingAdd, url)) continue;
            if (mine.removeIf(p -> p.feedUrl.equals(url))) removed++;
        }
        // The first sync: everything here goes up too.
        if (since == 0) {
            for (Podcasts.Podcast p : mine) if (!contains(pendingAdd, p.feedUrl)) pendingAdd.put(p.feedUrl);
        }
        Podcasts.saveSubscriptions(context, profileId, mine);
        long timestamp = changes.optLong("timestamp", since);
        if (pendingAdd.length() > 0 || pendingRemove.length() > 0) {
            JSONObject answer = new JSONObject(server.post(path, new JSONObject()
                    .put("add", pendingAdd).put("remove", pendingRemove).toString()));
            timestamp = answer.optLong("timestamp", timestamp);
            // The server may have tidied a URL: use its version here too.
            JSONArray updates = answer.optJSONArray("update_urls");
            for (int i = 0; updates != null && i < updates.length(); i++) {
                JSONArray pair = updates.optJSONArray(i);
                if (pair == null || pair.length() < 2 || pair.optString(1).isEmpty()) continue;
                String from = pair.optString(0), to = pair.optString(1);
                for (int j = 0; j < mine.size(); j++) {
                    Podcasts.Podcast p = mine.get(j);
                    if (p.feedUrl.equals(from)) mine.set(j, new Podcasts.Podcast(to, p.title, p.author, p.artwork, p.description));
                }
            }
            Podcasts.saveSubscriptions(context, profileId, mine);
        }
        state.put("pendingAdd", new JSONArray()).put("pendingRemove", new JSONArray());
        state.put("subsSince", timestamp);
        return new int[] {added, removed};
    }

    /** A podcast known only by its feed: its title and artwork from the feed, if it answers. */
    private static Podcasts.Podcast describe(String feedUrl) {
        try {
            return Podcasts.feed(feedUrl, false).podcast;
        } catch (IOException e) {
            String host = URI.create(feedUrl).getHost();
            return new Podcasts.Podcast(feedUrl, host == null ? feedUrl : host, "", "", "");
        }
    }

    /** Returns {updated here, sent}. */
    private static int[] syncEpisodes(Context context, String profileId, Server server, String device,
            JSONObject state) throws IOException, JSONException {
        String path = "/api/2/episodes/" + server.user + ".json";
        long since = state.optLong("actionsSince", 0);
        JSONObject answer = new JSONObject(server.get(path + "?aggregated=true&since=" + since));
        JSONArray actions = answer.optJSONArray("actions");
        // What each episode is, from the feeds it's in (read once each).
        Map<String, Podcasts.Feed> feeds = new HashMap<>();
        int updated = 0;
        for (int i = 0; actions != null && i < actions.length(); i++) {
            JSONObject a = actions.getJSONObject(i);
            if (!"play".equals(a.optString("action")) || device.equals(a.optString("device"))) continue;
            String episode = a.optString("episode"), podcast = a.optString("podcast");
            if (episode.isEmpty()) continue;
            long when = parseTimestamp(a.optString("timestamp"));
            Podcasts.Progress mine = progressOf(context, profileId, episode);
            if (mine != null && mine.updated >= when) continue;
            long position = a.optLong("position") * 1000, total = a.optLong("total") * 1000;
            boolean played = total > 0 && total - position < 60_000;
            AudioPlayer.Item item = itemFor(feeds, podcast, episode);
            Podcasts.putProgress(context, profileId, item, position, total, played, when);
            updated++;
        }
        long timestamp = answer.optLong("timestamp", since);
        JSONArray pending = list(state, "pendingActions");
        int sent = pending.length();
        if (sent > 0) {
            JSONArray out = new JSONArray(pending.toString());
            for (int i = 0; i < out.length(); i++) out.getJSONObject(i).put("device", device);
            JSONObject posted = new JSONObject(server.post(path, out.toString()));
            timestamp = Math.max(timestamp, posted.optLong("timestamp", timestamp));
        }
        state.put("pendingActions", new JSONArray());
        state.put("actionsSince", timestamp);
        return new int[] {updated, sent};
    }

    private static Podcasts.Progress progressOf(Context context, String profileId, String episode) {
        try {
            JSONObject p = new JSONObject(Settings.data(context, profileId, Settings.PODCAST_PROGRESS))
                    .optJSONObject(episode);
            return p == null ? null : new Podcasts.Progress(p.optLong("pos"), p.optLong("dur"),
                    p.optBoolean("played"), p.optLong("ts"));
        } catch (JSONException e) {
            return null;
        }
    }

    private static AudioPlayer.Item itemFor(Map<String, Podcasts.Feed> feeds, String podcast, String episode) {
        if (!podcast.isEmpty() && !feeds.containsKey(podcast) && feeds.size() < 30) {
            try {
                feeds.put(podcast, Podcasts.feed(podcast, false));
            } catch (IOException e) {
                feeds.put(podcast, null);
            }
        }
        Podcasts.Feed feed = feeds.get(podcast);
        if (feed != null) for (AudioPlayer.Item e : feed.episodes) if (e.id.equals(episode)) return e;
        // Not in the feed (any more): known by its URL only; the entry keeps what it knew.
        return new AudioPlayer.Item(AudioPlayer.PODCAST, episode, "", feed != null ? feed.podcast.title : "",
                feed != null ? feed.podcast.artwork : "", episode, podcast, 0, 0);
    }

    // ---- The server

    /** One profile's server: its address, and the login (Basic, plus the session cookie). */
    private static final class Server {
        final String base;
        final String user;
        final String auth;
        String cookie = "";

        Server(Context context, String profileId) {
            String url = Settings.string(context, profileId, Settings.PODCAST_SYNC_URL).trim();
            if (!url.matches("(?i)https?://.*")) url = "https://" + url;
            base = url.replaceAll("/+$", "");
            user = Settings.string(context, profileId, Settings.PODCAST_SYNC_USER).trim();
            String password = Settings.string(context, profileId, Settings.PODCAST_SYNC_PASSWORD);
            auth = "Basic " + Base64.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
        }

        void login() throws IOException {
            Http.Response r = call(Http.request("POST", base + "/api/2/auth/" + user + "/login.json")
                    .body("application/json", new byte[0]));
            if (r.status == 401) throw new IOException("The server didn't accept the user name or password");
            check(r);
            for (String c : r.headers("Set-Cookie")) {
                String pair = c.split(";", 2)[0];
                cookie = cookie.isEmpty() ? pair : cookie + "; " + pair;
            }
        }

        String get(String path) throws IOException {
            Http.Response r = call(Http.get(base + path));
            check(r);
            return r.text();
        }

        String post(String path, String json) throws IOException {
            Http.Response r = call(Http.request("POST", base + path).json(json));
            check(r);
            return r.text();
        }

        private Http.Response call(Http.Call call) throws IOException {
            call.header("Authorization", auth).header("Accept", "application/json").timeout(15_000);
            if (!cookie.isEmpty()) call.header("Cookie", cookie);
            return call.send();
        }

        private void check(Http.Response r) throws IOException {
            if (r.ok()) return;
            if (r.status == 404) throw new IOException("The server has no gPodder API at that address (404)");
            throw new IOException("The server answered " + r.status);
        }
    }

    // ---- Helpers

    private static JSONArray list(JSONObject state, String key) throws JSONException {
        JSONArray a = state.optJSONArray(key);
        if (a == null) {
            a = new JSONArray();
            state.put(key, a);
        }
        return a;
    }

    private static boolean contains(JSONArray a, String value) {
        for (int i = 0; i < a.length(); i++) if (value.equals(a.optString(i))) return true;
        return false;
    }

    private static void remove(JSONArray a, String value) {
        for (int i = a.length() - 1; i >= 0; i--) if (value.equals(a.optString(i))) a.remove(i);
    }

    /** gPodder's timestamps: UTC, "2009-12-12T09:00:00". */
    static String timestamp(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    static long parseTimestamp(String text) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(text.length() > 19 ? text.substring(0, 19) : text).getTime();
        } catch (ParseException | NullPointerException e) {
            return 0;
        }
    }

    static List<String> pendingSummary(Context context, String profileId) {
        JSONObject state = state(context, profileId);
        List<String> out = new ArrayList<>();
        long last = state.optLong("lastSync");
        out.add(last == 0 ? "Not synced yet" : "Last synced " + new SimpleDateFormat("d MMM HH:mm",
                Locale.getDefault()).format(new Date(last)));
        int waiting = state.optJSONArray("pendingActions") == null ? 0 : state.optJSONArray("pendingActions").length();
        if (waiting > 0) out.add(waiting + " episode positions waiting to send");
        return out;
    }
}
