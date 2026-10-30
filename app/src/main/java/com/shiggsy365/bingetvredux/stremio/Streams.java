package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/** Finding, filtering and sorting the streams for a film or episode. */
public final class Streams {
    public static final int SORT_QUALITY = 0, SORT_SIZE = 1, SORT_ADDON = 2;
    private static final long TIMEOUT_MS = 30_000;

    private Streams() {}

    /** What the user wants from streams (set in the web page's Playback settings). */
    public static final class Prefs {
        public int sort = SORT_QUALITY;
        /** Lines, e.g. 1080; 0 for no limit. */
        public int maxResolution;
        /** Bytes; 0 for no limit. Streams of unknown size are kept. */
        public long maxSize;
        public boolean hideCam = true;
        /** Lower-case keywords: streams mentioning any are hidden. */
        public List<String> exclude = new ArrayList<>();

        public static List<String> parseKeywords(String text) {
            List<String> out = new ArrayList<>();
            for (String k : (text == null ? "" : text).split(",")) {
                if (!k.trim().isEmpty()) out.add(k.trim().toLowerCase(Locale.ROOT));
            }
            return out;
        }
    }

    /** All the streams found, and the addons that failed. */
    public static final class Found {
        public final List<Stream> streams;
        public final List<String> errors;

        Found(List<Stream> streams, List<String> errors) {
            this.streams = streams;
            this.errors = errors;
        }
    }

    /**
     * Asks every addon with streams for this type and id at once. Streams come back in addon
     * order, then each addon's own order.
     */
    public static Found fetch(StremioClient client, AddonStore store, String type, String id) {
        List<AddonStore.Addon> addons = store.addonsFor("stream", type, id);
        List<Callable<JSONObject>> tasks = new ArrayList<>();
        for (AddonStore.Addon a : addons) {
            tasks.add(() -> client.resource(a.transportUrl, "stream", type, id, null));
        }
        List<Parallel.Result<JSONObject>> results = Parallel.run(tasks, TIMEOUT_MS);
        List<Stream> streams = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < addons.size(); i++) {
            Parallel.Result<JSONObject> r = results.get(i);
            String name = addons.get(i).manifest.name;
            if (!r.ok()) {
                errors.add(name + ": " + r.error.getMessage());
                continue;
            }
            JSONArray entries = r.value.optJSONArray("streams");
            for (int j = 0; entries != null && j < entries.length(); j++) {
                Stream s = Stream.fromJson(entries.optJSONObject(j), name, i);
                if (s != null) streams.add(s);
            }
        }
        return new Found(streams, errors);
    }

    /**
     * Whether a stream can be offered: direct links and YouTube only (torrents need a torrent
     * client this app doesn't have), and not filtered out by the user's settings.
     */
    public static boolean isAllowed(Stream s, Prefs prefs) {
        int kind = s.kind();
        if (kind != Stream.KIND_URL && kind != Stream.KIND_YOUTUBE) return false;
        Stream.Quality q = s.quality;
        if (prefs.hideCam && q.cam) return false;
        if (prefs.maxResolution > 0 && q.resolution > prefs.maxResolution) return false;
        if (prefs.maxSize > 0 && q.size > prefs.maxSize) return false;
        String text = s.text().toLowerCase(Locale.ROOT);
        for (String keyword : prefs.exclude) if (text.contains(keyword)) return false;
        return true;
    }

    /** Drops duplicates and filtered streams, then sorts the rest by preference. */
    public static List<Stream> prepare(List<Stream> streams, Prefs prefs) {
        Set<String> seen = new HashSet<>();
        List<Stream> allowed = new ArrayList<>();
        for (Stream s : streams) {
            if (!seen.add(s.dedupeKey()) || !isAllowed(s, prefs)) continue;
            allowed.add(s);
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < allowed.size(); i++) idx.add(i);
        Collections.sort(idx, (x, y) -> compare(allowed.get(x), x, allowed.get(y), y, prefs));
        List<Stream> out = new ArrayList<>();
        for (int i : idx) out.add(allowed.get(i));
        return out;
    }

    private static int compare(Stream a, int pa, Stream b, int pb, Prefs prefs) {
        int c;
        if (prefs.sort == SORT_SIZE) {
            c = Long.compare(b.quality.size, a.quality.size);
            if (c == 0) c = Integer.compare(b.quality.resolution, a.quality.resolution);
        } else if (prefs.sort == SORT_QUALITY) {
            c = Integer.compare(b.quality.resolution, a.quality.resolution);
            if (c == 0) c = Boolean.compare(b.quality.cached, a.quality.cached);
            if (c == 0) c = Long.compare(b.quality.size, a.quality.size);
        } else {
            c = 0;
        }
        if (c == 0) c = Integer.compare(a.addonIndex, b.addonIndex);
        if (c == 0) c = Integer.compare(pa, pb);
        return c;
    }

    /** Short technical tags for the picker: "4K · DV · REMUX · HEVC · 12.3 GB". */
    public static List<String> tags(Stream s) {
        Stream.Quality q = s.quality;
        List<String> tags = new ArrayList<>();
        if (q.resolution > 0) tags.add(q.resolution == 2160 ? "4K" : q.resolution + "p");
        tags.addAll(q.hdr);
        if (q.remux) tags.add("REMUX");
        if (!q.codec.isEmpty()) tags.add(q.codec);
        if (q.size > 0) tags.add(Stream.formatSize(q.size));
        if (q.cached) tags.add("Cached");
        return tags;
    }
}
