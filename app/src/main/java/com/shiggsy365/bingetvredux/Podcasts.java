package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.net.Uri;
import android.text.Html;
import android.util.Log;
import android.util.Xml;

import com.shiggsy365.bingetvredux.stremio.Http;
import com.shiggsy365.bingetvredux.stremio.MetaItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Podcasts: each profile's subscriptions and where it is in each episode, the feeds
 * themselves (RSS, read here), search (Podcast Index with the profile's key, else Apple's
 * directory) and Apple's top charts. A gPodder server can keep all of it in step with other
 * apps (see PodcastSync).
 */
final class Podcasts {
    private static final String TAG = "Podcasts";
    static final String PODCAST_PREFIX = "podcast:", EPISODE_PREFIX = "podep:";
    private static final long FEED_TTL_MS = 20 * 60_000L;
    private static final int PROGRESS_MAX = 400;
    private static final int LATEST_MAX = 40;
    // Under this, an episode hasn't really been started (for Continue listening).
    private static final long STARTED_MS = 30_000;

    /** A podcast: its feed and how it looks. */
    static final class Podcast {
        final String feedUrl;
        final String title;
        final String author;
        final String artwork;
        final String description;

        Podcast(String feedUrl, String title, String author, String artwork, String description) {
            this.feedUrl = feedUrl;
            this.title = title == null ? "" : title;
            this.author = author == null ? "" : author;
            this.artwork = artwork == null ? "" : artwork;
            this.description = description == null ? "" : description;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("feedUrl", feedUrl).put("title", title).put("author", author)
                    .put("artwork", artwork);
        }

        static Podcast fromJson(JSONObject o) {
            return new Podcast(o.optString("feedUrl"), o.optString("title"), o.optString("author"),
                    o.optString("artwork"), o.optString("description"));
        }
    }

    /** A feed as read: the podcast and its episodes, newest first. */
    static final class Feed {
        final Podcast podcast;
        final List<AudioPlayer.Item> episodes;
        // Each episode's notes, by media URL.
        final Map<String, String> notes;
        final long readAt;

        Feed(Podcast podcast, List<AudioPlayer.Item> episodes, Map<String, String> notes, long readAt) {
            this.podcast = podcast;
            this.episodes = episodes;
            this.notes = notes;
            this.readAt = readAt;
        }
    }

    /** Where a profile is in an episode. */
    static final class Progress {
        final long positionMs;
        final long durationMs;
        final boolean played;
        final long updated;

        Progress(long positionMs, long durationMs, boolean played, long updated) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.played = played;
            this.updated = updated;
        }
    }

    private static final Map<String, Feed> sFeeds = new ConcurrentHashMap<>();
    // Podcasts and episodes seen this run, so a card can be opened or played.
    private static final Map<String, Podcast> sPodcasts = new ConcurrentHashMap<>();
    private static final Map<String, AudioPlayer.Item> sEpisodes = new ConcurrentHashMap<>();

    private Podcasts() {}

    // ---- Subscriptions

    static List<Podcast> subscriptions(Context context) {
        return subscriptionsOf(Settings.data(context, Settings.PODCASTS));
    }

    static List<Podcast> subscriptions(Context context, String profileId) {
        return subscriptionsOf(Settings.data(context, profileId, Settings.PODCASTS));
    }

    static List<Podcast> subscriptionsOf(String json) {
        List<Podcast> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                Podcast p = Podcast.fromJson(list.getJSONObject(i));
                if (!p.feedUrl.isEmpty()) {
                    out.add(p);
                    sPodcasts.put(p.feedUrl, p);
                }
            }
        } catch (JSONException e) {
            // None yet.
        }
        return out;
    }

    static boolean isSubscribed(Context context, String feedUrl) {
        for (Podcast p : subscriptions(context)) if (p.feedUrl.equals(feedUrl)) return true;
        return false;
    }

    static void saveSubscriptions(Context context, String profileId, List<Podcast> list) {
        JSONArray out = new JSONArray();
        try {
            for (Podcast p : list) out.put(p.toJson());
        } catch (JSONException e) {
            return;
        }
        Settings.putData(context, profileId, Settings.PODCASTS, out.toString());
    }

    /** Subscribes or unsubscribes (and tells the sync server); returns whether subscribed now. */
    static boolean toggleSubscription(Context context, Podcast podcast) {
        String profile = Profiles.current(context).id;
        List<Podcast> list = subscriptions(context, profile);
        boolean removed = list.removeIf(p -> p.feedUrl.equals(podcast.feedUrl));
        if (!removed) list.add(0, podcast);
        saveSubscriptions(context, profile, list);
        PodcastSync.subscriptionChanged(context, profile, podcast.feedUrl, !removed);
        return !removed;
    }

    // ---- Progress

    private static JSONObject progressJson(Context context, String profileId) {
        try {
            return new JSONObject(Settings.data(context, profileId, Settings.PODCAST_PROGRESS));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static Progress progress(Context context, String mediaUrl) {
        JSONObject p = progressJson(context, Profiles.current(context).id).optJSONObject(mediaUrl);
        if (p == null) return null;
        return new Progress(p.optLong("pos"), p.optLong("dur"), p.optBoolean("played"), p.optLong("ts"));
    }

    /** Where to start an episode: where it was left, or the start if it was finished. */
    static long resumeAt(Context context, String mediaUrl) {
        Progress p = progress(context, mediaUrl);
        if (p == null || p.played) return 0;
        return p.positionMs;
    }

    /** Saves the place in an episode, from the player; the sync server hears of it too. */
    static void saveProgress(Context context, AudioPlayer.Item item, long positionMs, long durationMs,
            boolean finished) {
        String profile = Profiles.current(context).id;
        long now = System.currentTimeMillis();
        // Within the last minute or so counts as finished.
        boolean played = finished || (durationMs > 0 && durationMs - positionMs < 60_000);
        putProgress(context, profile, item, positionMs, durationMs, played, now);
        PodcastSync.played(context, profile, item, positionMs, durationMs);
    }

    static synchronized void putProgress(Context context, String profileId, AudioPlayer.Item item,
            long positionMs, long durationMs, boolean played, long updated) {
        JSONObject all = progressJson(context, profileId);
        try {
            JSONObject entry = new JSONObject().put("pos", positionMs).put("dur", durationMs)
                    .put("played", played).put("ts", updated);
            JSONObject old = all.optJSONObject(item.id);
            JSONObject about = item.title.isEmpty() && old != null ? old.optJSONObject("item") : item.toJson();
            if (about != null) entry.put("item", about);
            all.put(item.id, entry);
            trim(all);
            Settings.putData(context, profileId, Settings.PODCAST_PROGRESS, all.toString());
        } catch (JSONException e) {
            Log.w(TAG, "Saving progress: " + e.getMessage());
        }
    }

    /** Keeps the most recently changed entries. */
    private static void trim(JSONObject all) {
        if (all.length() <= PROGRESS_MAX) return;
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) keys.add(it.next());
        keys.sort((a, b) -> Long.compare(all.optJSONObject(a).optLong("ts"), all.optJSONObject(b).optLong("ts")));
        for (int i = 0; i < keys.size() - PROGRESS_MAX; i++) all.remove(keys.get(i));
    }

    /** Marks an episode played, or not (from its page). */
    static void setPlayed(Context context, AudioPlayer.Item item, boolean played) {
        String profile = Profiles.current(context).id;
        Progress old = progress(context, item.id);
        long duration = old != null ? old.durationMs : item.durationS * 1000;
        long position = played ? duration : 0;
        putProgress(context, profile, item, position, duration, played, System.currentTimeMillis());
        PodcastSync.played(context, profile, item, position, duration);
    }

    /** Episodes started and not finished, most recent first. */
    static List<AudioPlayer.Item> inProgress(Context context) {
        JSONObject all = progressJson(context, Profiles.current(context).id);
        List<JSONObject> entries = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject e = all.optJSONObject(it.next());
            if (e != null && !e.optBoolean("played") && e.optLong("pos") >= STARTED_MS
                    && e.optJSONObject("item") != null) {
                entries.add(e);
            }
        }
        entries.sort((a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
        List<AudioPlayer.Item> out = new ArrayList<>();
        for (JSONObject e : entries) {
            AudioPlayer.Item item = AudioPlayer.Item.fromJson(e.optJSONObject("item"));
            if (item.url.isEmpty()) continue;
            sEpisodes.put(item.id, item);
            out.add(item);
        }
        return out;
    }

    // ---- Feeds

    /** A podcast's feed, from memory while it's fresh. Blocks. */
    static Feed feed(String feedUrl, boolean fresh) throws IOException {
        Feed cached = sFeeds.get(feedUrl);
        if (cached != null && (!fresh || System.currentTimeMillis() - cached.readAt < FEED_TTL_MS)) {
            return cached;
        }
        try (Http.Body body = Http.get(feedUrl).timeout(15_000).open()) {
            if (!body.ok()) throw new IOException("feed answered " + body.status);
            Feed feed = parse(feedUrl, body.in);
            sFeeds.put(feedUrl, feed);
            sPodcasts.put(feedUrl, feed.podcast);
            for (AudioPlayer.Item e : feed.episodes) sEpisodes.put(e.id, e);
            return feed;
        } catch (XmlPullParserException e) {
            throw new IOException("not a podcast feed");
        }
    }

    /** The newest episodes across the profile's subscriptions. */
    static List<AudioPlayer.Item> latest(Context context) {
        List<Callable<Feed>> tasks = new ArrayList<>();
        for (Podcast p : subscriptions(context)) tasks.add(() -> feed(p.feedUrl, true));
        List<AudioPlayer.Item> all = new ArrayList<>();
        for (com.shiggsy365.bingetvredux.stremio.Parallel.Result<Feed> r
                : com.shiggsy365.bingetvredux.stremio.Parallel.run(tasks, 25_000)) {
            if (!r.ok()) continue;
            for (int i = 0; i < Math.min(5, r.value.episodes.size()); i++) all.add(r.value.episodes.get(i));
        }
        all.sort((a, b) -> Long.compare(b.published, a.published));
        return all.size() > LATEST_MAX ? new ArrayList<>(all.subList(0, LATEST_MAX)) : all;
    }

    private static Feed parse(String feedUrl, InputStream in) throws XmlPullParserException, IOException {
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        x.setInput(in, null);
        String title = "", author = "", artwork = "", description = "";
        List<String[]> items = new ArrayList<>();
        // The item being read: title, guid, url, date, duration, image, notes.
        String[] item = null;
        boolean inImage = false;
        for (int event = x.getEventType(); event != XmlPullParser.END_DOCUMENT; event = x.next()) {
            if (event == XmlPullParser.START_TAG) {
                String tag = x.getName();
                if (tag.equals("item")) {
                    item = new String[] {"", "", "", "", "", "", ""};
                } else if (tag.equals("image") && item == null) {
                    inImage = true;
                } else if (tag.equals("itunes:image")) {
                    String href = x.getAttributeValue(null, "href");
                    if (href != null) {
                        if (item != null) item[5] = href;
                        else artwork = href;
                    }
                } else if (tag.equals("enclosure") && item != null) {
                    String url = x.getAttributeValue(null, "url");
                    if (url != null) item[2] = url.trim();
                } else if (item != null) {
                    switch (tag) {
                        case "title": item[0] = text(x); break;
                        case "guid": item[1] = text(x); break;
                        case "pubDate": item[3] = text(x); break;
                        case "itunes:duration": item[4] = text(x); break;
                        case "description":
                        case "itunes:summary":
                        case "content:encoded": {
                            String t = text(x);
                            if (t.length() > item[6].length()) item[6] = t;
                            break;
                        }
                        default: break;
                    }
                } else {
                    switch (tag) {
                        case "title": if (title.isEmpty()) title = text(x); break;
                        case "url": if (inImage && artwork.isEmpty()) artwork = text(x); break;
                        case "itunes:author": author = text(x); break;
                        case "description":
                        case "itunes:summary": {
                            String t = text(x);
                            if (t.length() > description.length()) description = t;
                            break;
                        }
                        default: break;
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (x.getName().equals("item") && item != null) {
                    if (!item[2].isEmpty()) items.add(item);
                    item = null;
                } else if (x.getName().equals("image")) {
                    inImage = false;
                }
            }
        }
        Podcast podcast = new Podcast(feedUrl, title.trim(), author.trim(), artwork.trim(), plain(description));
        List<AudioPlayer.Item> episodes = new ArrayList<>();
        Map<String, String> notes = new ConcurrentHashMap<>();
        for (String[] i : items) {
            String image = i[5].isEmpty() ? podcast.artwork : i[5];
            AudioPlayer.Item e = new AudioPlayer.Item(AudioPlayer.PODCAST, i[2], i[0].trim(), podcast.title,
                    image, i[2], feedUrl, date(i[3]), seconds(i[4]));
            episodes.add(e);
            notes.put(e.id, plain(i[6]));
        }
        episodes.sort((a, b) -> Long.compare(b.published, a.published));
        return new Feed(podcast, episodes, notes, System.currentTimeMillis());
    }

    private static String text(XmlPullParser x) throws XmlPullParserException, IOException {
        String t = x.nextText();
        return t == null ? "" : t;
    }

    /** HTML show notes as plain text. */
    static String plain(String html) {
        if (html == null || html.isEmpty()) return "";
        CharSequence text = android.os.Build.VERSION.SDK_INT >= 24
                ? Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) : fromHtmlOld(html);
        return text.toString().replaceAll("\\s*\\n\\s*\\n\\s*", "\n\n").trim();
    }

    @SuppressWarnings("deprecation")
    private static CharSequence fromHtmlOld(String html) {
        return Html.fromHtml(html);
    }

    /** "Tue, 10 Jun 2025 04:00:00 +0000" (and its usual variations) in ms; 0 if unreadable. */
    static long date(String text) {
        text = text.trim();
        if (text.isEmpty()) return 0;
        for (String pattern : new String[] {"EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz",
                "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssX"}) {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ENGLISH);
            ParsePosition at = new ParsePosition(0);
            java.util.Date d = f.parse(text, at);
            if (d != null) return d.getTime();
        }
        return 0;
    }

    /** "1:02:03", "62:03" or "3723" as seconds; 0 if unreadable. */
    static long seconds(String text) {
        text = text.trim();
        if (text.isEmpty()) return 0;
        long total = 0;
        try {
            for (String part : text.split(":")) total = total * 60 + (long) Double.parseDouble(part);
        } catch (NumberFormatException e) {
            return 0;
        }
        return total;
    }

    // ---- Directories

    /** Podcasts matching {@code term}: Podcast Index with the profile's key, else Apple's. */
    static List<Podcast> search(Context context, String term) throws IOException {
        return search(context, Profiles.current(context).id, term);
    }

    static List<Podcast> search(Context context, String profileId, String term) throws IOException {
        String key = Settings.string(context, profileId, Settings.PODCAST_INDEX_KEY).trim();
        String secret = Settings.string(context, profileId, Settings.PODCAST_INDEX_SECRET).trim();
        List<Podcast> out = new ArrayList<>();
        try {
            if (!key.isEmpty() && !secret.isEmpty()) {
                String date = String.valueOf(System.currentTimeMillis() / 1000);
                Http.Response r = Http.get("https://api.podcastindex.org/api/1.0/search/byterm?max=30&q="
                        + Uri.encode(term)).header("X-Auth-Key", key).header("X-Auth-Date", date)
                        .header("Authorization", sha1(key + secret + date)).timeout(10_000).send();
                if (!r.ok()) throw new IOException("Podcast Index answered " + r.status);
                JSONArray feeds = new JSONObject(r.text()).optJSONArray("feeds");
                for (int i = 0; feeds != null && i < feeds.length(); i++) {
                    JSONObject f = feeds.getJSONObject(i);
                    String art = f.optString("artwork");
                    add(out, new Podcast(f.optString("url"), f.optString("title"), f.optString("author"),
                            art.isEmpty() ? f.optString("image") : art, plain(f.optString("description"))));
                }
                return out;
            }
            Http.Response r = Http.get("https://itunes.apple.com/search?media=podcast&limit=30&term="
                    + Uri.encode(term)).timeout(10_000).send();
            if (!r.ok()) throw new IOException("Apple's directory answered " + r.status);
            JSONArray results = new JSONObject(r.text()).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject p = results.getJSONObject(i);
                add(out, new Podcast(p.optString("feedUrl"), p.optString("collectionName"),
                        p.optString("artistName"), p.optString("artworkUrl600"), ""));
            }
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        return out;
    }

    private static void add(List<Podcast> list, Podcast p) {
        if (p.feedUrl.isEmpty() || p.title.isEmpty()) return;
        sPodcasts.put(p.feedUrl, p);
        list.add(p);
    }

    /** Apple's top podcasts in a country (two letters). */
    static List<Podcast> top(String country) throws IOException {
        List<Podcast> out = new ArrayList<>();
        try {
            Http.Response chart = Http.get("https://rss.marketingtools.apple.com/api/v2/"
                    + country.toLowerCase(Locale.ROOT) + "/podcasts/top/40/podcasts.json")
                    .timeout(10_000).send();
            if (!chart.ok()) throw new IOException("Apple's chart answered " + chart.status);
            JSONArray results = new JSONObject(chart.text()).getJSONObject("feed").optJSONArray("results");
            List<String> ids = new ArrayList<>();
            for (int i = 0; results != null && i < results.length(); i++) ids.add(results.getJSONObject(i).optString("id"));
            if (ids.isEmpty()) return out;
            // The chart has no feeds: Apple's lookup has them, in any order.
            Http.Response lookup = Http.get("https://itunes.apple.com/lookup?entity=podcast&id="
                    + String.join(",", ids)).timeout(10_000).send();
            JSONArray found = new JSONObject(lookup.text()).optJSONArray("results");
            Map<String, Podcast> byId = new ConcurrentHashMap<>();
            for (int i = 0; found != null && i < found.length(); i++) {
                JSONObject p = found.getJSONObject(i);
                if (p.optString("feedUrl").isEmpty()) continue;
                byId.put(String.valueOf(p.optLong("collectionId")), new Podcast(p.optString("feedUrl"),
                        p.optString("collectionName"), p.optString("artistName"), p.optString("artworkUrl600"), ""));
            }
            for (String id : ids) {
                Podcast p = byId.get(id);
                if (p != null) add(out, p);
            }
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        return out;
    }

    private static String sha1(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Cards

    static Podcast podcast(String metaId) {
        return sPodcasts.get(metaId.substring(PODCAST_PREFIX.length()));
    }

    static AudioPlayer.Item episode(String metaId) {
        return sEpisodes.get(metaId.substring(EPISODE_PREFIX.length()));
    }

    static MetaItem podcastMeta(Podcast p) {
        try {
            MetaItem m = MetaItem.preview(new JSONObject().put("id", PODCAST_PREFIX + p.feedUrl)
                    .put("type", "podcast").put("name", p.title).put("poster", p.artwork)
                    .put("posterShape", "square").put("description", p.description), null);
            return m == null ? null : m.forRow(p.author, p.description);
        } catch (JSONException e) {
            return null;
        }
    }

    static List<MetaItem> podcastMetas(List<Podcast> list) {
        List<MetaItem> out = new ArrayList<>();
        for (Podcast p : list) {
            MetaItem m = podcastMeta(p);
            if (m != null) out.add(m);
        }
        return out;
    }

    static MetaItem episodeMeta(Context context, AudioPlayer.Item e) {
        try {
            String notes = "";
            Feed feed = sFeeds.get(e.feedUrl);
            if (feed != null && feed.notes.get(e.id) != null) notes = feed.notes.get(e.id);
            MetaItem m = MetaItem.preview(new JSONObject().put("id", EPISODE_PREFIX + e.id)
                    .put("type", "episode").put("name", e.title).put("poster", e.artwork)
                    .put("posterShape", "square").put("description", notes), null);
            return m == null ? null : m.forRow(episodeLine(context, e), notes);
        } catch (JSONException ex) {
            return null;
        }
    }

    static List<MetaItem> episodeMetas(Context context, List<AudioPlayer.Item> list) {
        List<MetaItem> out = new ArrayList<>();
        for (AudioPlayer.Item e : list) {
            MetaItem m = episodeMeta(context, e);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** "The Rest Is Politics  ·  3 days ago  ·  52 min left". */
    static String episodeLine(Context context, AudioPlayer.Item e) {
        List<String> parts = new ArrayList<>();
        if (!e.subtitle.isEmpty()) parts.add(e.subtitle);
        if (e.published > 0) parts.add(ago(e.published));
        Progress p = progress(context, e.id);
        if (p != null && p.played) {
            parts.add("Played");
        } else if (p != null && p.positionMs >= STARTED_MS && p.durationMs > 0) {
            parts.add(Math.max(1, (p.durationMs - p.positionMs) / 60_000) + " min left");
        } else if (e.durationS > 0) {
            parts.add(Math.max(1, e.durationS / 60) + " min");
        }
        return String.join("  ·  ", parts);
    }

    static String ago(long when) {
        long days = (System.currentTimeMillis() - when) / 86_400_000L;
        if (days <= 0) return "Today";
        if (days == 1) return "Yesterday";
        if (days < 7) return days + " days ago";
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new java.util.Date(when));
    }
}
