package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One stream an addon offers for a film or episode, with what its labels say about it. */
public final class Stream {
    public static final int KIND_URL = 0, KIND_YOUTUBE = 1, KIND_TORRENT = 2, KIND_EXTERNAL = 3;

    public final String addon;
    public final int addonIndex;
    /** The addon's short label, e.g. "AIOStreams 4K". */
    public final String name;
    /** The addon's longer description: often the file name, size and source. */
    public final String title;
    public final String url;
    public final String ytId;
    public final String infoHash;
    public final String externalUrl;
    /** Headers the request needs. */
    public final Map<String, String> headers;
    public final String filename;
    public final long videoSize;
    public final String videoHash;
    /** Streams of a show with the same binge group are from the same source. */
    public final String bingeGroup;
    public final List<Subtitles.Track> subtitles;
    public final Quality quality;

    private Stream(String addon, int addonIndex, JSONObject data) {
        JSONObject hints = data.optJSONObject("behaviorHints");
        if (hints == null) hints = new JSONObject();
        JSONObject proxy = hints.optJSONObject("proxyHeaders");
        JSONObject request = proxy == null ? null : proxy.optJSONObject("request");
        Map<String, String> h = new LinkedHashMap<>();
        if (request != null) {
            for (java.util.Iterator<String> it = request.keys(); it.hasNext(); ) {
                String k = it.next();
                h.put(k, request.optString(k));
            }
        }
        this.addon = addon;
        this.addonIndex = addonIndex;
        name = cleanText(data.optString("name", ""));
        String t = data.optString("title", "");
        title = cleanText(t.isEmpty() ? data.optString("description", "") : t);
        url = data.optString("url", "");
        ytId = data.optString("ytId", "");
        infoHash = data.optString("infoHash", "").toLowerCase(Locale.ROOT);
        externalUrl = data.optString("externalUrl", "");
        headers = Collections.unmodifiableMap(h);
        filename = hints.optString("filename", "");
        videoSize = hints.optLong("videoSize", 0);
        videoHash = hints.optString("videoHash", "");
        bingeGroup = hints.optString("bingeGroup", "");
        List<Subtitles.Track> subs = new ArrayList<>();
        JSONArray subsJson = data.optJSONArray("subtitles");
        for (int i = 0; subsJson != null && i < subsJson.length(); i++) {
            Subtitles.Track track = Subtitles.Track.fromJson(subsJson.optJSONObject(i), addon);
            if (track != null) subs.add(track);
        }
        subtitles = subs;
        quality = Quality.parse(name + "\n" + title + "\n" + filename, videoSize, name);
    }

    /** Null if the entry has nothing that could be played or opened. */
    public static Stream fromJson(JSONObject data, String addon, int addonIndex) {
        if (data == null) return null;
        for (String k : new String[]{"url", "ytId", "infoHash", "externalUrl"}) {
            if (!data.optString(k, "").isEmpty()) return new Stream(addon, addonIndex, data);
        }
        return null;
    }

    public int kind() {
        if (!url.isEmpty()) return KIND_URL;
        if (!ytId.isEmpty()) return KIND_YOUTUBE;
        if (!infoHash.isEmpty()) return KIND_TORRENT;
        return KIND_EXTERNAL;
    }

    /** Everything the addon says about it, for filtering by keyword. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{name, title, filename}) {
            if (!s.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append(s);
        }
        return sb.toString();
    }

    /** Two streams with the same key are the same stream. */
    public String dedupeKey() {
        switch (kind()) {
            case KIND_URL: return "url|" + url;
            case KIND_TORRENT: return "torrent|" + infoHash + "|" + filename;
            case KIND_YOUTUBE: return "yt|" + ytId;
            default: return "ext|" + externalUrl;
        }
    }

    /** Hints the subtitles resource takes, as Stremio sends them. */
    public Map<String, String> subtitleExtra() {
        Map<String, String> extra = new LinkedHashMap<>();
        if (!videoHash.isEmpty()) extra.put("videoHash", videoHash);
        if (videoSize > 0) extra.put("videoSize", String.valueOf(videoSize));
        if (!filename.isEmpty()) extra.put("filename", filename);
        return extra;
    }

    private static final Pattern INVISIBLE = Pattern.compile("[\\u200b-\\u200f\\u2060\\ufeff]");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

    /** Drops zero-width characters (some addons pad labels with them) and squeezes spaces. */
    static String cleanText(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String clean = SPACES.matcher(INVISIBLE.matcher(line).replaceAll("")).replaceAll(" ").trim();
            if (!clean.isEmpty()) out.append(out.length() > 0 ? "\n" : "").append(clean);
        }
        return out.toString();
    }

    /** What a stream's labels say about its picture, sound and size. */
    public static final class Quality {
        private static final Object[][] RESOLUTIONS = {
                {2160, Pattern.compile("\\b(2160p|4k|uhd)\\b", Pattern.CASE_INSENSITIVE)},
                {1440, Pattern.compile("\\b1440p\\b", Pattern.CASE_INSENSITIVE)},
                {1080, Pattern.compile("\\b(1080p|fhd)\\b", Pattern.CASE_INSENSITIVE)},
                {720, Pattern.compile("\\b720p\\b", Pattern.CASE_INSENSITIVE)},
                {576, Pattern.compile("\\b576p\\b", Pattern.CASE_INSENSITIVE)},
                {480, Pattern.compile("\\b(480p|sd|dvd(rip)?)\\b", Pattern.CASE_INSENSITIVE)},
                {360, Pattern.compile("\\b360p\\b", Pattern.CASE_INSENSITIVE)},
        };
        private static final Object[][] HDR = {
                {"DV", Pattern.compile("\\b(dv|dovi|dolby[ ._-]?vision)\\b", Pattern.CASE_INSENSITIVE)},
                {"HDR10+", Pattern.compile("\\bhdr10(\\+|plus)", Pattern.CASE_INSENSITIVE)},
                {"HDR", Pattern.compile("\\bhdr(10)?\\b", Pattern.CASE_INSENSITIVE)},
        };
        private static final Object[][] CODECS = {
                {"AV1", Pattern.compile("\\bav1\\b", Pattern.CASE_INSENSITIVE)},
                {"HEVC", Pattern.compile("\\b(x265|h[ .]?265|hevc)\\b", Pattern.CASE_INSENSITIVE)},
                {"VP9", Pattern.compile("\\bvp9\\b", Pattern.CASE_INSENSITIVE)},
                {"H.264", Pattern.compile("\\b(x264|h[ .]?264|avc)\\b", Pattern.CASE_INSENSITIVE)},
        };
        private static final Pattern SIZE = Pattern.compile(
                "(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|GiB|MB|MiB)\\b", Pattern.CASE_INSENSITIVE);
        private static final Pattern REMUX = Pattern.compile("\\bremux\\b", Pattern.CASE_INSENSITIVE);
        private static final Pattern CAM = Pattern.compile(
                "\\b(cam|camrip|hdcam|hd-?ts|telesync|ts|telecine|hd-?tc)\\b",
                Pattern.CASE_INSENSITIVE);
        // Debrid addons mark instantly available ("cached") results like "[RD+]" or with ⚡.
        private static final Pattern CACHED = Pattern.compile(
                "\\[(RD|AD|PM|DL|TB|ED|OC|PK|EN|DR)\\+\\]|⚡|\\binstant\\b",
                Pattern.CASE_INSENSITIVE);

        /** Lines, e.g. 2160; 0 when unknown. */
        public final int resolution;
        /** "DV", "HDR10+" or "HDR". */
        public final List<String> hdr;
        public final String codec;
        /** Bytes; 0 when unknown. */
        public final long size;
        public final boolean remux;
        public final boolean cam;
        public final boolean cached;

        private Quality(int resolution, List<String> hdr, String codec, long size,
                boolean remux, boolean cam, boolean cached) {
            this.resolution = resolution;
            this.hdr = hdr;
            this.codec = codec;
            this.size = size;
            this.remux = remux;
            this.cam = cam;
            this.cached = cached;
        }

        /** The addon's own short label's resolution wins over the longer text's. */
        static Quality parse(String text, long size, String label) {
            int resolution = resolution(label);
            if (resolution == 0) resolution = resolution(text);
            List<String> hdr = new ArrayList<>();
            for (Object[] h : HDR) if (((Pattern) h[1]).matcher(text).find()) hdr.add((String) h[0]);
            if (hdr.contains("HDR10+")) hdr.remove("HDR");
            String codec = "";
            for (Object[] c : CODECS) {
                if (((Pattern) c[1]).matcher(text).find()) {
                    codec = (String) c[0];
                    break;
                }
            }
            if (size <= 0) {
                Matcher m = SIZE.matcher(text);
                if (m.find()) {
                    double value = Double.parseDouble(m.group(1).replace(',', '.'));
                    String unit = m.group(2).toLowerCase(Locale.ROOT);
                    // GB/MB as addons mean them (decimal); GiB/MiB binary.
                    long factor = unit.equals("tb") ? 1_000_000_000_000L
                            : unit.equals("gb") ? 1_000_000_000L : unit.equals("gib") ? 1L << 30
                            : unit.equals("mb") ? 1_000_000L : 1L << 20;
                    size = (long) (value * factor);
                }
            }
            return new Quality(resolution, hdr, codec, Math.max(size, 0),
                    REMUX.matcher(text).find(), CAM.matcher(text).find(),
                    CACHED.matcher(text).find());
        }

        private static int resolution(String text) {
            for (Object[] r : RESOLUTIONS) {
                if (((Pattern) r[1]).matcher(text).find()) return (Integer) r[0];
            }
            return 0;
        }
    }

    /** Decimal units, as addons and file managers show sizes. */
    public static String formatSize(long size) {
        if (size <= 0) return "";
        if (size >= 1_000_000_000_000L) return String.format(Locale.ROOT, "%.1f TB", size / 1e12);
        if (size >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1f GB", size / 1e9);
        if (size >= 1_000_000L) return String.format(Locale.ROOT, "%.0f MB", size / 1e6);
        return size + " B";
    }
}
