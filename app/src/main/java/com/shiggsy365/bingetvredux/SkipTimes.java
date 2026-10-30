package com.shiggsy365.bingetvredux;

import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.Http;
import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Video;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where an episode's intro, recap and end credits are, from community databases: IntroDB
 * (by IMDb id, season and episode) for any show, then AniSkip (by MyAnimeList id and episode)
 * for anime. Neither needs an account. An episode neither knows has no segments.
 */
final class SkipTimes {
    private static final String TAG = "SkipTimes";
    private static final String INTRODB = "https://api.introdb.app/segments";
    private static final String ANISKIP = "https://api.aniskip.com/v2/skip-times/";
    private static final String ARM = "https://arm.haglund.dev/api/v2/";
    private static final int TIMEOUT_MS = 8_000;
    // Answers for this run of the app, by video id (an empty list: none known).
    private static final Map<String, List<Segment>> sFound = Collections.synchronizedMap(new HashMap<>());

    static final String INTRO = "intro", RECAP = "recap", CREDITS = "credits";

    /** A part of the episode that can be skipped. */
    static final class Segment {
        final String kind;
        final long startMs;
        final long endMs;

        Segment(String kind, long startMs, long endMs) {
            this.kind = kind;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        boolean contains(long positionMs) {
            return positionMs >= startMs && positionMs < endMs;
        }

        /** "Skip intro" and so on. */
        String label() {
            return "Skip " + kind;
        }
    }

    private SkipTimes() {}

    /** The episode's segments, in order; empty for a film or when none are known. Blocks. */
    static List<Segment> find(MetaItem meta, Video video) {
        if (video == null || video.season == Video.NONE || video.episode == Video.NONE) {
            return Collections.emptyList();
        }
        List<Segment> known = sFound.get(video.id);
        if (known != null) return known;
        List<Segment> found = new ArrayList<>();
        try {
            String imdb = meta.ids.get("imdb");
            if (imdb != null) found = introDb(imdb, video.season, video.episode);
            if (found.isEmpty() && maybeAnime(meta)) {
                int[] mal = malEpisode(meta, video);
                if (mal != null) found = aniSkip(mal[0], mal[1]);
            }
        } catch (IOException | JSONException e) {
            // Unknown this time; asked again next time the episode plays.
            Log.d(TAG, "lookup failed: " + e.getMessage());
            return Collections.emptyList();
        }
        Collections.sort(found, (a, b) -> Long.compare(a.startMs, b.startMs));
        Log.i(TAG, video.id + ": " + found.size() + " segments");
        sFound.put(video.id, found);
        return found;
    }

    // ---- IntroDB

    private static List<Segment> introDb(String imdb, int season, int episode)
            throws IOException, JSONException {
        Http.Response r = Http.get(INTRODB + "?imdb_id=" + imdb + "&season=" + season
                + "&episode=" + episode).timeout(TIMEOUT_MS).send();
        List<Segment> segments = new ArrayList<>();
        if (r.status == 404) return segments;
        if (!r.ok()) throw new IOException("IntroDB " + r.status);
        JSONObject data = new JSONObject(r.text());
        addIntroDb(segments, data.optJSONObject("intro"), INTRO);
        addIntroDb(segments, data.optJSONObject("recap"), RECAP);
        addIntroDb(segments, data.optJSONObject("outro"), CREDITS);
        return segments;
    }

    private static void addIntroDb(List<Segment> segments, JSONObject part, String kind) {
        if (part == null) return;
        long start = part.optLong("start_ms", -1), end = part.optLong("end_ms", -1);
        if (start >= 0 && end > start) segments.add(new Segment(kind, start, end));
    }

    // ---- AniSkip

    private static boolean maybeAnime(MetaItem meta) {
        if (meta.id.startsWith("kitsu:") || meta.id.startsWith("mal:")) return true;
        for (String g : meta.genres) {
            String genre = g.toLowerCase(Locale.ROOT);
            if (genre.equals("anime") || genre.equals("animation")) return true;
        }
        return false;
    }

    /** The MyAnimeList id and that entry's episode number, or null if unknown. */
    private static int[] malEpisode(MetaItem meta, Video video) throws IOException, JSONException {
        if (meta.id.startsWith("mal:")) return new int[] {Integer.parseInt(meta.id.substring(4)), video.episode};
        if (meta.id.startsWith("kitsu:")) {
            JSONObject ids = new JSONObject(armGet("ids?source=kitsu&id=" + meta.id.substring(6)
                    + "&include=myanimelist"));
            int mal = ids.optInt("myanimelist", 0);
            return mal > 0 ? new int[] {mal, video.episode} : null;
        }
        String imdb = meta.ids.get("imdb");
        if (imdb == null) return null;
        JSONArray entries = new JSONArray(armGet("imdb?id=" + imdb
                + "&include=myanimelist,themoviedb-season,thetvdb-season"));
        // Each MyAnimeList entry is usually one season; a long show can be one entry for all.
        List<JSONObject> withMal = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (e.optInt("myanimelist", 0) > 0) withMal.add(e);
        }
        for (JSONObject e : withMal) {
            if (e.optInt("themoviedb-season", -1) == video.season
                    || e.optInt("thetvdb-season", -1) == video.season) {
                return new int[] {e.getInt("myanimelist"), video.episode};
            }
        }
        if (withMal.size() == 1) {
            return new int[] {withMal.get(0).getInt("myanimelist"), absoluteEpisode(meta, video)};
        }
        return null;
    }

    /** The episode's number counting every season before it (season 0, specials, aside). */
    static int absoluteEpisode(MetaItem meta, Video video) {
        int before = 0;
        for (Video v : meta.videos) if (v.season >= 1 && v.season < video.season) before++;
        return before + video.episode;
    }

    private static String armGet(String path) throws IOException {
        Http.Response r = Http.get(ARM + path).timeout(TIMEOUT_MS).send();
        if (!r.ok()) throw new IOException("ARM " + r.status);
        return r.text();
    }

    private static List<Segment> aniSkip(int mal, int episode) throws IOException, JSONException {
        Http.Response r = Http.get(ANISKIP + mal + "/" + episode
                + "?types=op&types=ed&types=recap&types=mixed-op&types=mixed-ed&episodeLength=0")
                .timeout(TIMEOUT_MS).send();
        List<Segment> segments = new ArrayList<>();
        // Nothing known comes back as a 404 with found: false.
        if (r.status == 404) return segments;
        if (!r.ok()) throw new IOException("AniSkip " + r.status);
        JSONObject data = new JSONObject(r.text());
        JSONArray results = data.optJSONArray("results");
        if (results == null) return segments;
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);
            JSONObject interval = result.optJSONObject("interval");
            if (interval == null) continue;
            String type = result.optString("skipType");
            String kind = type.endsWith("op") ? INTRO : type.endsWith("ed") ? CREDITS
                    : type.equals("recap") ? RECAP : null;
            long start = Math.round(interval.optDouble("startTime", -1) * 1000);
            long end = Math.round(interval.optDouble("endTime", -1) * 1000);
            if (kind != null && start >= 0 && end > start) segments.add(new Segment(kind, start, end));
        }
        return segments;
    }
}
