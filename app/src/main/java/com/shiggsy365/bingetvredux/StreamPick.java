package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.view.Display;
import android.view.WindowManager;

import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.StreamInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which streams suit this TV: none above the highest output mode it offers, and first the first
 * in the profile's order in the profile's language that the connection can keep up with, as the
 * player has measured it. The language comes before the connection: a stream that stutters can
 * be swapped, one in Russian is no use at all.
 */
final class StreamPick {
    private static final String PREFS = "stream_pick";
    private static final String CONNECTION_MBPS = "connectionMbps";
    // The connection needs room to spare, for a stream's busier scenes.
    private static final double HEADROOM = 0.8;
    private static final Pattern BITRATE = Pattern.compile("([0-9.]+)\\s*([KMG])bps", Pattern.CASE_INSENSITIVE);
    // Speeds measured are averaged with what was known, so one slow evening doesn't decide.
    private static final double NEW_SAMPLE_WEIGHT = 0.5;
    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04ff]");
    // "2xRus", "3xUkr": that many tracks in that language.
    private static final Pattern TRACK_COUNT = Pattern.compile("^\\d+x");
    /** How well a stream's languages suit the profile. */
    static final int OTHER_LANGUAGE = 0, ALSO_OTHER_LANGUAGES = 1, IN_LANGUAGE = 2;
    private static final Pattern AFTER_TITLE = Pattern.compile(
            "(?:^|[^0-9a-z])(?:(?:19|20)\\d\\d|s\\d{1,2}[ ._-]?e\\d{1,3})(?=[^0-9a-z]|$)", Pattern.CASE_INSENSITIVE);
    /** Language tags seen in release names, as ISO 639-2 codes. */
    private static final Map<String, String> LANGUAGE_TAGS = new HashMap<>();
    static {
        tags("eng", "eng", "english");
        // With the Russian dubbing studios' names, which stand in for a language tag.
        tags("rus", "rus", "russian", "rudub", "mvo", "dvo", "avo", "lostfilm", "hdrezka",
                "newstudio", "baibako", "coldfilm", "jaskier");
        tags("ita", "ita", "italian");
        tags("deu", "ger", "deu", "german");
        tags("fra", "fre", "fra", "french", "truefrench", "vff", "vf2", "vfi", "vfq");
        tags("spa", "spa", "esp", "spanish", "latino", "castellano");
        tags("hin", "hin", "hindi");
        tags("tam", "tam", "tamil");
        tags("tel", "tel", "telugu");
        tags("kor", "kor", "korean");
        tags("jpn", "jpn", "jap", "japanese");
        tags("zho", "chi", "zho", "chinese", "mandarin");
        tags("por", "por", "portuguese", "dublado");
        tags("pol", "pol", "pl", "polish", "lektor");
        tags("tur", "tur", "turkish");
        tags("ukr", "ukr", "ukrainian");
        tags("ara", "ara", "arabic");
    }

    private static void tags(String language, String... tags) {
        for (String tag : tags) LANGUAGE_TAGS.put(tag, language);
    }

    /** What the choice goes on: 0 for a bitrate or height that isn't known. */
    static final class Candidate {
        final double mbps;
        final int height;
        final boolean ready;
        /** {@link #IN_LANGUAGE}, {@link #ALSO_OTHER_LANGUAGES} or {@link #OTHER_LANGUAGE}. */
        final int language;

        Candidate(double mbps, int height, boolean ready) {
            this(mbps, height, ready, IN_LANGUAGE);
        }

        Candidate(double mbps, int height, boolean ready, int language) {
            this.mbps = mbps;
            this.height = height;
            this.ready = ready;
            this.language = language;
        }
    }

    private StreamPick() {
    }

    /**
     * The positions of the heights this screen can show: all of them when it doesn't say, or
     * when that would leave nothing to play. A height of 0 isn't known, and stays.
     */
    static List<Integer> withinScreen(int[] heights, int screenHeight) {
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < heights.length; i++) {
            if (screenHeight <= 0 || heights[i] <= screenHeight) kept.add(i);
        }
        if (kept.isEmpty()) for (int i = 0; i < heights.length; i++) kept.add(i);
        return kept;
    }

    /** {@code streams} without those above the highest mode the TV can be set to. */
    static List<Stream> forThisScreen(Context context, List<Stream> streams) {
        int[] heights = new int[streams.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = height(StreamInfo.of(streams.get(i)).resolution);
        List<Stream> out = new ArrayList<>();
        for (int i : withinScreen(heights, screenHeight(context))) out.add(streams.get(i));
        return out;
    }

    /**
     * The first, by position, that's only in the profile's language and fits the connection; else
     * one that also has others (their dub may be what plays); else either kind, connection or
     * not; only then another language. 0 when nothing's ready. A connection of 0 isn't applied.
     */
    static int best(List<Candidate> candidates, double connectionMbps) {
        // Each step: the languages it takes, and whether it must fit the connection.
        int[][] steps = {{IN_LANGUAGE, 1}, {ALSO_OTHER_LANGUAGES, 1}, {IN_LANGUAGE, 0},
                {ALSO_OTHER_LANGUAGES, 0}, {OTHER_LANGUAGE, 1}, {OTHER_LANGUAGE, 0}};
        for (int[] step : steps) {
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                if (!c.ready || c.language != step[0]) continue;
                if (step[1] == 1 && connectionMbps > 0 && c.mbps > connectionMbps * HEADROOM) continue;
                return i;
            }
        }
        return 0;
    }

    /**
     * How well a stream's languages suit: its addon's list (less "sub", which is subtitles), and
     * the tags after the year or episode in its file name (so "The.Italian.Job.2003" isn't
     * Italian). Nothing said counts as the profile's language; a file name in Cyrillic, as
     * Russian.
     */
    static int language(String languages, String filename, List<String> wanted) {
        String file = filename == null ? "" : filename;
        boolean want = false, other = false, multi = false;
        Matcher title = AFTER_TITLE.matcher(file);
        if (title.find()) {
            for (String tag : file.substring(title.end()).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                tag = TRACK_COUNT.matcher(tag).replaceFirst("");
                if (tag.equals("multi") || tag.equals("dual")) multi = true;
                String language = LANGUAGE_TAGS.get(tag);
                if (language == null) continue;
                if (wanted.contains(language)) want = true;
                else other = true;
            }
        }
        if (CYRILLIC.matcher(file).find() && !wanted.contains("rus")) other = true;
        List<String> listed = new ArrayList<>();
        for (String part : (languages == null ? "" : languages).replaceAll("(?i)\\bsub\\b.*", "").split("[·,]")) {
            if (!part.trim().isEmpty()) listed.add(part.trim());
        }
        if (!listed.isEmpty()) {
            boolean listedWanted = false;
            for (String language : Settings.languages(String.join(",", listed))) {
                if (wanted.contains(language)) listedWanted = true;
                else other = true;
            }
            if (!listedWanted) return OTHER_LANGUAGE;
            want = true;
        }
        if (other && !want && !multi) return OTHER_LANGUAGE;
        return other || multi ? ALSO_OTHER_LANGUAGES : IN_LANGUAGE;
    }

    static double mbps(String bitrate) {
        Matcher m = BITRATE.matcher(bitrate == null ? "" : bitrate);
        if (!m.find()) return 0;
        double value = Double.parseDouble(m.group(1));
        switch (m.group(2).toUpperCase(Locale.ROOT)) {
            case "K": return value / 1000;
            case "G": return value * 1000;
            default: return value;
        }
    }

    static int height(String resolution) {
        switch (resolution == null ? "" : resolution) {
            case "4k": return 2160;
            case "1440p": return 1440;
            case "1080p": return 1080;
            case "720p": return 720;
            case "sd": return 480;
            default: return 0;
        }
    }

    /** {@code streams} with the one that suits this TV moved to the front. */
    static List<Stream> bestFirst(Context context, List<Stream> streams) {
        if (streams.size() < 2) return streams;
        List<Candidate> candidates = new ArrayList<>(streams.size());
        List<String> wanted = Settings.audioLanguages(context);
        for (Stream s : streams) {
            StreamInfo info = StreamInfo.of(s);
            candidates.add(new Candidate(mbps(info.bitrate), height(info.resolution),
                    !info.p2p && (info.debrid.isEmpty() || info.cached),
                    language(info.languages, info.filename, wanted)));
        }
        int best = best(candidates, connectionMbps(context));
        if (best == 0) return streams;
        List<Stream> out = new ArrayList<>(streams);
        out.add(0, out.remove(best));
        return out;
    }

    static double connectionMbps(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(CONNECTION_MBPS, 0);
    }

    static void saveConnection(Context context, long bitsPerSecond) {
        if (bitsPerSecond <= 0) return;
        double known = connectionMbps(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(CONNECTION_MBPS, (float) averaged(known, bitsPerSecond / 1_000_000d)).apply();
    }

    /** The connection known, with a new measurement taken into account. */
    static double averaged(double known, double measured) {
        return known <= 0 ? measured : known + (measured - known) * NEW_SAMPLE_WEIGHT;
    }

    /** The highest of the display's output modes; 0 when it doesn't say. */
    private static int screenHeight(Context context) {
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int height = 0;
        for (Display.Mode mode : display.getSupportedModes()) height = Math.max(height, mode.getPhysicalHeight());
        return height;
    }
}
