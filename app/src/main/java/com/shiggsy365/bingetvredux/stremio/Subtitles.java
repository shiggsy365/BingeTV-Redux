package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** Subtitles from subtitle addons such as OpenSubtitles. */
public final class Subtitles {
    private static final long TIMEOUT_MS = 15_000;

    private Subtitles() {}

    public static final class Track {
        public final String url;
        /** Usually an ISO 639-2 code such as "eng"; sometimes a name. */
        public final String lang;
        public final String id;
        public final String addon;

        Track(String url, String lang, String id, String addon) {
            this.url = url;
            this.lang = lang;
            this.id = id;
            this.addon = addon;
        }

        static Track fromJson(JSONObject data, String addon) {
            if (data == null || data.optString("url", "").isEmpty()) return null;
            return new Track(data.optString("url"), data.optString("lang", ""),
                    data.optString("id", ""), addon);
        }
    }

    /**
     * Asks every subtitles addon at once, with the stream's hints (hash, size, file name) where
     * there are any. Tracks in {@code languages} (ISO 639-2 codes, in order of preference) come
     * first, in that order; with an empty list, every language is kept.
     */
    public static List<Track> fetch(StremioClient client, AddonStore store, String type,
            String id, Stream stream, List<String> languages) {
        List<AddonStore.Addon> addons = store.addonsFor("subtitles", type, id);
        List<Map.Entry<String, String>> extra = new ArrayList<>();
        if (stream != null) {
            for (Map.Entry<String, String> e : stream.subtitleExtra().entrySet()) {
                extra.add(new SimpleEntry<>(e));
            }
        }
        List<Callable<JSONObject>> tasks = new ArrayList<>();
        for (AddonStore.Addon a : addons) {
            tasks.add(() -> client.resource(a.transportUrl, "subtitles", type, id, extra));
        }
        List<Parallel.Result<JSONObject>> results = Parallel.run(tasks, TIMEOUT_MS);
        List<Track> tracks = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < addons.size(); i++) {
            Parallel.Result<JSONObject> r = results.get(i);
            JSONArray subs = r.ok() ? r.value.optJSONArray("subtitles") : null;
            for (int j = 0; subs != null && j < subs.length(); j++) {
                Track t = Track.fromJson(subs.optJSONObject(j), addons.get(i).manifest.name);
                if (t != null && urls.add(t.url)) tracks.add(t);
            }
        }
        return preferred(tracks, languages);
    }

    /** The tracks in the preferred languages, best first; all of them if none are set. */
    static List<Track> preferred(List<Track> tracks, List<String> languages) {
        if (languages == null || languages.isEmpty()) return tracks;
        List<Track> out = new ArrayList<>();
        for (String lang : languages) {
            for (Track t : tracks) if (sameLanguage(t.lang, lang)) out.add(t);
        }
        return out;
    }

    /** "eng", "en" and "English" are the same language. */
    static boolean sameLanguage(String a, String b) {
        a = normalize(a);
        b = normalize(b);
        return !a.isEmpty() && a.equals(b);
    }

    private static String normalize(String lang) {
        lang = (lang == null ? "" : lang).trim().toLowerCase(Locale.ROOT);
        if (lang.length() == 2) {
            try {
                return Locale.forLanguageTag(lang).getISO3Language();
            } catch (java.util.MissingResourceException e) {
                return lang;
            }
        }
        if (lang.length() > 3) {
            for (Locale l : Locale.getAvailableLocales()) {
                if (l.getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(lang)) {
                    try {
                        return l.getISO3Language();
                    } catch (java.util.MissingResourceException e) {
                        return lang;
                    }
                }
            }
        }
        // ISO 639-2/B codes some addons use for the /T ones Java gives.
        switch (lang) {
            case "ger": return "deu";
            case "fre": return "fra";
            case "dut": return "nld";
            case "chi": return "zho";
            case "cze": return "ces";
            case "gre": return "ell";
            case "rum": return "ron";
            case "slo": return "slk";
            case "per": return "fas";
            default: return lang;
        }
    }
}
