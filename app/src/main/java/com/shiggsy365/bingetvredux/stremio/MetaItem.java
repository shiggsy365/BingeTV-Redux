package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A film, show or other item. From a catalog it's a preview (no videos); from the meta resource
 * it's complete, with a show's episodes.
 */
public final class MetaItem {
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");
    private static final Pattern DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*m", Pattern.CASE_INSENSITIVE);

    public static final String CAST = "cast", DIRECTOR = "director", WRITER = "writer";

    public final String id;
    public final String type;
    public final String name;
    public final String poster;
    /** "poster", "landscape" or "square". */
    public final String posterShape;
    public final String background;
    public final String logo;
    public final String description;
    public final String releaseInfo;
    /** 0 when unknown. */
    public final double imdbRating;
    public final List<String> genres;
    public final String runtime;
    public final List<Person> people;
    /** A YouTube id, or "". */
    public final String trailer;
    public final String released;
    public final String country;
    /** Age rating, the local one preferred (e.g. "15", "TV-MA"). */
    public final String certification;
    public final List<Video> videos;
    public final String defaultVideoId;

    public static final class Person {
        public final String name;
        public final String job;
        /** The character, for cast. */
        public final String role;
        public final String photo;

        Person(String name, String job, String role, String photo) {
            this.name = name;
            this.job = job;
            this.role = role;
            this.photo = photo;
        }
    }

    private MetaItem(JSONObject data, String id, String type, String name, List<Video> videos) {
        this.id = id;
        this.type = type;
        this.name = name;
        poster = Artwork.sized(data.optString("poster", ""), Artwork.POSTER);
        posterShape = shape(data.optString("posterShape", ""));
        background = Artwork.sized(data.optString("background", ""), Artwork.BACKGROUND);
        logo = Artwork.sized(data.optString("logo", ""), Artwork.LOGO);
        description = data.optString("description", "");
        String info = firstNonEmpty(data, "releaseInfo", "year", "released");
        releaseInfo = info;
        imdbRating = rating(data.opt("imdbRating"));
        List<String> g = Manifest.strings(data.optJSONArray("genres"));
        genres = g.isEmpty() ? Manifest.strings(data.optJSONArray("genre")) : g;
        Object rt = data.opt("runtime");
        runtime = rt == null || rt == JSONObject.NULL ? "" : String.valueOf(rt);
        people = parsePeople(data);
        trailer = trailer(data);
        released = data.optString("released", "");
        country = data.optString("country", "");
        certification = certification(data);
        this.videos = videos;
        JSONObject hints = data.optJSONObject("behaviorHints");
        defaultVideoId = hints == null ? "" : hints.optString("defaultVideoId", "");
    }

    /** A catalog entry, or null for entries that can't be shown. */
    public static MetaItem preview(JSONObject data, String defaultType) {
        return parse(data, defaultType, false);
    }

    /** A full meta object, or null if it's unusable. */
    public static MetaItem full(JSONObject data, String defaultType) {
        return parse(data, defaultType, true);
    }

    private static MetaItem parse(JSONObject data, String defaultType, boolean withVideos) {
        if (data == null) return null;
        String id = data.optString("id", "");
        String name = data.optString("name", "");
        String type = data.optString("type", "");
        if (type.isEmpty() && defaultType != null) type = defaultType;
        if (id.isEmpty() || name.isEmpty() || type.isEmpty()) return null;
        List<Video> videos = new ArrayList<>();
        JSONArray v = withVideos ? data.optJSONArray("videos") : null;
        for (int i = 0; v != null && i < v.length(); i++) {
            Video video = Video.fromJson(v.optJSONObject(i));
            if (video != null) videos.add(video);
        }
        return new MetaItem(data, id, type, name, videos);
    }

    public int year() {
        Matcher m = YEAR.matcher(releaseInfo);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** {@code YYYY-MM-DD} or "". */
    public String premiered() {
        Matcher m = DATE.matcher(released);
        return m.find() ? m.group(1) : "";
    }

    /** Seconds, or 0 when unknown ("142 min", "2h 22min" or "142"). */
    public int runtimeSeconds() {
        return parseRuntime(runtime);
    }

    public List<Person> people(String job) {
        List<Person> out = new ArrayList<>();
        for (Person p : people) if (p.job.equals(job)) out.add(p);
        return out;
    }

    /** A show (or anything else with seasons), rather than a single film. */
    public boolean hasSeasons() {
        for (Video v : videos) if (v.season != Video.NONE) return true;
        return "series".equals(type);
    }

    /** Season numbers in display order; specials (season 0) last. */
    public List<Integer> seasons() {
        Set<Integer> seasons = new HashSet<>();
        for (Video v : videos) if (v.season != Video.NONE) seasons.add(v.season);
        List<Integer> out = new ArrayList<>(seasons);
        Collections.sort(out, (a, b) -> a == 0 ? (b == 0 ? 0 : 1) : b == 0 ? -1 : a - b);
        return out;
    }

    /** One season's episodes, in order. */
    public List<Video> episodes(int season) {
        List<Video> out = new ArrayList<>();
        for (Video v : videos) if (v.season == season) out.add(v);
        Collections.sort(out, (a, b) -> Integer.compare(
                a.episode == Video.NONE ? Integer.MAX_VALUE : a.episode,
                b.episode == Video.NONE ? Integer.MAX_VALUE : b.episode));
        return out;
    }

    public Video video(String videoId) {
        for (Video v : videos) if (v.id.equals(videoId)) return v;
        return null;
    }

    /** IMDb/TMDB/TVDB… ids from the item's id and any *_id fields. */
    public static Map<String, String> externalIds(String stremioId, JSONObject data) {
        Map<String, String> ids = new TreeMap<>();
        String base = stremioId.startsWith("tt") ? stremioId.split(":")[0] : stremioId;
        String[][] prefixes = {{"tt", "imdb"}, {"tmdb:", "tmdb"}, {"tvdb:", "tvdb"},
                {"kitsu:", "kitsu"}, {"mal:", "mal"}};
        for (String[] p : prefixes) {
            if (base.startsWith(p[0])) {
                ids.put(p[1], p[1].equals("imdb") ? base
                        : base.substring(p[0].length()).split(":")[0]);
                break;
            }
        }
        String[][] fields = {{"imdb_id", "imdb"}, {"imdbId", "imdb"}, {"tmdb_id", "tmdb"},
                {"moviedb_id", "tmdb"}, {"tvdb_id", "tvdb"}};
        for (String[] f : fields) {
            if (data == null || ids.containsKey(f[1])) continue;
            Object value = data.opt(f[0]);
            if (value == null || value == JSONObject.NULL) continue;
            String s = String.valueOf(value).trim();
            if (s.isEmpty() || (f[1].equals("imdb") && !s.startsWith("tt"))) continue;
            ids.put(f[1], s);
        }
        return ids;
    }

    static int parseRuntime(String text) {
        text = text == null ? "" : text.trim();
        if (text.matches("\\d+")) return Integer.parseInt(text) * 60;
        Matcher h = HOURS.matcher(text), m = MINUTES.matcher(text);
        boolean hasH = h.find(), hasM = m.find();
        if (!hasH && !hasM) return 0;
        return (hasH ? Integer.parseInt(h.group(1)) * 3600 : 0)
                + (hasM ? Integer.parseInt(m.group(1)) * 60 : 0);
    }

    // ---- Parsing helpers ----

    private static String shape(String shape) {
        shape = shape.toLowerCase(Locale.ROOT);
        return shape.equals("landscape") || shape.equals("square") ? shape : "poster";
    }

    private static String firstNonEmpty(JSONObject data, String... keys) {
        for (String k : keys) {
            Object v = data.opt(k);
            if (v != null && v != JSONObject.NULL && !String.valueOf(v).isEmpty()) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    /** A 0–10 rating, or 0 for missing, unreadable or "0" (unrated). */
    static double rating(Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        try {
            double r = Double.parseDouble(String.valueOf(value));
            return r > 0 && r <= 10 ? r : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String trailer(JSONObject data) {
        JSONArray trailers = data.optJSONArray("trailers");
        for (int i = 0; trailers != null && i < trailers.length(); i++) {
            JSONObject t = trailers.optJSONObject(i);
            if (t == null || !t.optString("type", "Trailer").equals("Trailer")) continue;
            String id = t.optString("ytId", t.optString("source", ""));
            if (!id.isEmpty()) return id;
        }
        JSONArray streams = data.optJSONArray("trailerStreams");
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject t = streams.optJSONObject(i);
            if (t != null && !t.optString("ytId", "").isEmpty()) return t.optString("ytId");
        }
        return "";
    }

    private static String certification(JSONObject data) {
        JSONObject extras = data.optJSONObject("app_extras");
        String[] values = {
                extras == null ? "" : extras.optString("certificationLocal", ""),
                extras == null ? "" : extras.optString("certification", ""),
                data.optString("certification", "")};
        for (String v : values) if (!v.trim().isEmpty()) return v.trim();
        return "";
    }

    /**
     * Cast and crew, richest source first: app_extras (photos and characters, from
     * AIOMetadata), then cast/director/writer (names or objects), then Stremio's links.
     */
    private static List<Person> parsePeople(JSONObject data) {
        JSONObject extras = data.optJSONObject("app_extras");
        JSONArray links = data.optJSONArray("links");
        Map<String, Person> people = new LinkedHashMap<>();
        String[][] jobs = {{DIRECTOR, "directors", "director", "Directors"},
                {WRITER, "writers", "writer", "Writers"}, {CAST, "cast", "cast", "Cast"}};
        for (String[] job : jobs) {
            JSONArray fromExtras = extras == null ? null : extras.optJSONArray(job[1]);
            for (int i = 0; fromExtras != null && i < fromExtras.length(); i++) {
                JSONObject p = fromExtras.optJSONObject(i);
                if (p != null) addPerson(people, job[0], p.optString("name", ""),
                        p.optString("character", ""), p.optString("photo", ""));
            }
            Object raw = data.opt(job[2]);
            List<Object> entries = new ArrayList<>();
            if (raw instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) raw).length(); i++) entries.add(((JSONArray) raw).opt(i));
            } else if (raw != null) {
                entries.add(raw);
            }
            for (Object entry : entries) {
                if (entry instanceof JSONObject) {
                    JSONObject p = (JSONObject) entry;
                    addPerson(people, job[0], p.optString("name", ""),
                            p.optString("character", ""), p.optString("photo", ""));
                } else if (entry instanceof String) {
                    for (String n : ((String) entry).split(",")) addPerson(people, job[0], n, "", "");
                }
            }
            for (int i = 0; links != null && i < links.length(); i++) {
                JSONObject l = links.optJSONObject(i);
                if (l != null && job[3].equals(l.optString("category"))) {
                    addPerson(people, job[0], l.optString("name", ""), "", "");
                }
            }
        }
        return new ArrayList<>(people.values());
    }

    private static void addPerson(Map<String, Person> people, String job, String name,
            String role, String photo) {
        name = name.trim();
        String key = job + "|" + name;
        if (name.isEmpty() || people.containsKey(key)) return;
        people.put(key, new Person(name, job, role.equals(name) ? "" : role, photo));
    }
}
