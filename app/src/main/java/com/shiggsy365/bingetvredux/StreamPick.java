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
 * in the profile's order that the connection can keep up with, as the player measured last time.
 */
final class StreamPick {
    private static final String PREFS = "stream_pick";
    private static final String CONNECTION_MBPS = "connectionMbps";
    // The connection needs room to spare, for a stream's busier scenes.
    private static final double HEADROOM = 0.8;
    private static final Pattern BITRATE = Pattern.compile("([0-9.]+)\\s*([KMG])bps", Pattern.CASE_INSENSITIVE);
    private static final Pattern AFTER_TITLE = Pattern.compile(
            "(?:^|[^0-9a-z])(?:(?:19|20)\\d\\d|s\\d{1,2}[ ._-]?e\\d{1,3})(?=[^0-9a-z]|$)", Pattern.CASE_INSENSITIVE);
    /** Language tags seen in release names, as ISO 639-2 codes. */
    private static final Map<String, String> LANGUAGE_TAGS = new HashMap<>();
    static {
        tags("eng", "eng", "english");
        tags("rus", "rus", "russian", "rudub");
        tags("ita", "ita", "italian");
        tags("deu", "ger", "deu", "german");
        tags("fra", "fre", "fra", "french", "vff");
        tags("spa", "spa", "esp", "spanish", "latino", "castellano");
        tags("hin", "hin", "hindi");
        tags("tam", "tam", "tamil");
        tags("tel", "tel", "telugu");
        tags("kor", "kor", "korean");
        tags("jpn", "jpn", "jap", "japanese");
        tags("zho", "chi", "zho", "chinese", "mandarin");
        tags("por", "por", "portuguese", "dublado");
        tags("pol", "pol", "polish", "lektor");
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
        final boolean language;

        Candidate(double mbps, int height, boolean ready) {
            this(mbps, height, ready, true);
        }

        Candidate(double mbps, int height, boolean ready, boolean language) {
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
     * The first that fits, by position, in the right language if any is; 0 when none fits. A
     * connection of 0 isn't applied.
     */
    static int best(List<Candidate> candidates, double connectionMbps) {
        int fits = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (!c.ready) continue;
            if (connectionMbps > 0 && c.mbps > connectionMbps * HEADROOM) continue;
            if (c.language) return i;
            if (fits < 0) fits = i;
        }
        return Math.max(fits, 0);
    }

    /**
     * False only when a stream says it's in other languages: its addon's list, or else the tags
     * after the year or episode in its file name (so "The.Italian.Job.2003" isn't Italian).
     */
    static boolean inLanguage(String languages, String filename, List<String> wanted) {
        if (languages != null && !languages.isEmpty()) {
            for (String language : Settings.languages(languages.replace('·', ','))) {
                if (wanted.contains(language)) return true;
            }
            return false;
        }
        Matcher title = AFTER_TITLE.matcher(filename == null ? "" : filename);
        if (!title.find()) return true;
        boolean tagged = false;
        for (String tag : filename.substring(title.end()).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tag.equals("multi") || tag.equals("dual")) return true;
            String language = LANGUAGE_TAGS.get(tag);
            if (language == null) continue;
            if (wanted.contains(language)) return true;
            tagged = true;
        }
        return !tagged;
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
                    inLanguage(info.languages, info.filename, wanted)));
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(CONNECTION_MBPS, bitsPerSecond / 1_000_000f).apply();
    }

    /** The highest of the display's output modes; 0 when it doesn't say. */
    private static int screenHeight(Context context) {
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int height = 0;
        for (Display.Mode mode : display.getSupportedModes()) height = Math.max(height, mode.getPhysicalHeight());
        return height;
    }
}
