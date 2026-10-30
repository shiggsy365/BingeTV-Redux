package com.shiggsy365.bingetvredux.stremio;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the stream list shows for a stream: badges (resolution, release, visual, audio, channels,
 * streaming network), cached/debrid status, size, bitrate, release group and languages, and its
 * text made readable.
 *
 * Addons describe streams in free text, often decorated with symbols and "small capital"
 * letters ("ɴᴇᴛғʟɪx", "ᴹᵇᵖˢ", "▣ HEVC ✦ DV"). Everything is normalised to plain text and read
 * from the addon's label, description and filename. An addon can instead send one
 * {@code key: value} per line (see {@link #STRUCTURED_KEYS}); those values are used where
 * present. (A port of Stremio Bridge's streaminfo.py.)
 */
public final class StreamInfo {
    // ---- text

    private static final String SMALL_CAPS_FROM = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢ";
    private static final String SMALL_CAPS_TO = "abcdefghijklmnopqrstuvwyz";
    // Symbols addons use as icons or decoration (but not "+", ":" or "-", which mean something).
    private static final Pattern DECORATION = Pattern.compile(
            "[\\u2190-\\u2bff\\u2e80-\\u2fff\\u3000-\\u303f\\x{1F000}-\\x{1FAFF}»«•|]");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200b-\\u200f\\u2060\\ufeff\\ufe0f]");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

    /**
     * Plain, drawable text: small capitals and super/subscripts become ordinary letters, icons
     * and decoration go, runs of spaces collapse.
     */
    public static String normalise(String text) {
        if (text == null) return "";
        StringBuilder plain = new StringBuilder();
        for (char c : INVISIBLE.matcher(text).replaceAll("").toCharArray()) {
            int i = SMALL_CAPS_FROM.indexOf(c);
            plain.append(i >= 0 ? SMALL_CAPS_TO.charAt(i) : c);
        }
        String nfkc = Normalizer.normalize(plain, Normalizer.Form.NFKC);
        List<String> lines = new ArrayList<>();
        for (String line : nfkc.split("\\r?\\n")) {
            line = SPACES.matcher(DECORATION.matcher(line).replaceAll(" ")).replaceAll(" ");
            line = strip(line, " ·-");
            if (!line.isEmpty()) lines.add(line);
        }
        return String.join("\n", lines);
    }

    private static String strip(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    // ---- badges

    /** How each badge reads in the cards' text lines (and on the badge itself). */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        String[] pairs = {"remux", "Remux", "bluray", "BluRay", "webdl", "WEB-DL", "webrip", "WEBRip",
                "hdtv", "HDTV", "dvd", "DVD", "cam", "CAM", "dv", "DV", "hdr10plus", "HDR10+",
                "hdr10", "HDR10", "hdr", "HDR", "sdr", "SDR", "imax", "IMAX",
                "imax_enhanced", "IMAX Enhanced", "3d", "3D", "seadex", "SeaDex", "atmos", "Atmos",
                "truehd", "TrueHD", "dtsx", "DTS:X", "dtshdma", "DTS-HD MA", "dtshd", "DTS-HD",
                "dts", "DTS", "ddp", "DD+", "dd", "DD", "flac", "FLAC", "opus", "OPUS", "aac", "AAC",
                "mp3", "MP3", "pcm", "PCM", "4k", "4K", "1440p", "1440p", "1080p", "1080p",
                "720p", "720p", "sd", "SD"};
        for (int i = 0; i < pairs.length; i += 2) LABELS.put(pairs[i], pairs[i + 1]);
    }

    public static final List<String> RESOLUTION_ORDER = Arrays.asList("4k", "1440p", "1080p", "720p", "sd");

    private static final String[][] RES_TEXT = {{"4k", "2160[pi]?|4k|uhd"}, {"1440p", "1440[pi]?|2k|qhd"},
            {"1080p", "1080[pi]?|fhd"}, {"720p", "720[pi]?"}, {"sd", "576[pi]?"}, {"sd", "480[pi]?|sd"},
            {"sd", "360p"}};
    private static final String[][] RELEASES = {  // first match wins
            {"remux", "remux"}, {"bluray", "blu-?ray|bdrip|brrip|bdremux|bd\\d+"}, {"webrip", "web-?rip"},
            {"webdl", "web-?dl|webdl|web"}, {"hdtv", "hdtv|pdtv|hdrip"}, {"dvd", "dvd(?:rip|r|5|9)?"},
            {"cam", "cam(?:rip)?|hdcam|hd-?ts|telesync|ts|telecine|hd-?tc|tc"}};
    private static final String[][] AUDIO = {
            {"atmos", "atmos"}, {"truehd", "true-?hd"}, {"dtsx", "dts[ :.-]?x"},
            {"dtshdma", "dts[ .-]?hd[ .-]?ma"}, {"dtshd", "dts[ .-]?hd"}, {"dts", "dts"},
            {"ddp", "ddp|dd\\+|e-?ac-?3|dolby digital plus"}, {"dd", "dd|ac-?3|dolby digital"},
            {"flac", "flac"}, {"opus", "opus"}, {"aac", "aac"}, {"mp3", "mp3"}, {"pcm", "l?pcm"}};
    private static final List<String> AUDIO_RANK = Arrays.asList("atmos", "dtsx", "dtshdma", "dtshd",
            "dts", "truehd", "ddp", "dd", "flac", "opus", "aac", "mp3", "pcm");
    private static final Pattern CHANNELS = Pattern.compile("(?<!\\d)([1-7])[ .]([01])(?!\\d)");
    /** {key, filename tags, names}. */
    private static final String[][][] NETWORKS = {
            {{"netflix"}, {"NF"}, {"netflix"}}, {{"amazon"}, {"AMZN"}, {"amazon", "prime video"}},
            {{"appletv"}, {"ATVP", "ATV"}, {"apple tv"}}, {{"disney"}, {"DSNP", "DNSP"}, {"disney"}},
            {{"max"}, {"HMAX", "MAX"}, {"hbo max", "hbo"}}, {{"hulu"}, {"HULU"}, {"hulu"}},
            {{"peacock"}, {"PCOK"}, {"peacock"}}, {{"paramount"}, {"PMTP"}, {"paramount"}},
            {{"crunchyroll"}, {"CR"}, {"crunchyroll"}}};
    public static final Map<String, String> DEBRID = new LinkedHashMap<>();
    static {
        String[] pairs = {"RD", "Real-Debrid", "AD", "AllDebrid", "PM", "Premiumize", "TB", "TorBox",
                "DL", "Debrid-Link", "ED", "EasyDebrid", "OC", "Offcloud", "PK", "PikPak", "ND", "Usenet",
                "EN", "Easynews", "PKP", "PikPak", "DR", "Debrider"};
        for (int i = 0; i < pairs.length; i += 2) DEBRID.put(pairs[i], pairs[i + 1]);
    }
    private static final Pattern DEBRID_CODE = Pattern.compile("\\[([A-Z]{2,3})(\\+?)\\]");
    private static final Pattern CACHED_TEXT = Pattern.compile("⚡|\\[[A-Z]{2,3}\\+\\]|\\binstant\\b|\\bcached\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BITRATE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([MK])bps", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE_TEXT = Pattern.compile("(?<![\\w.])(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|MB)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP = Pattern.compile("-([A-Za-z0-9]{2,15})(?:\\.[a-z0-9]{2,4})?$");

    /** One "key: value" per line, for addons that can format their output for us. */
    public static final List<String> STRUCTURED_KEYS = Arrays.asList("res", "src", "vis", "aud", "ch", "size",
            "br", "svc", "cached", "type", "net", "grp", "lang", "prov");
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*([a-z]+)\\s*:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean find(String pattern, String text) {
        return PATTERNS.computeIfAbsent(pattern, p -> Pattern.compile(
                "(?<![a-z0-9])(?:" + p + ")(?![a-z0-9])", Pattern.CASE_INSENSITIVE)).matcher(text).find();
    }

    // ---- the stream's info

    public final String resolution;   // badge keys, "" when unknown
    public final String release;
    public final List<String> visual; // ["dv", "hdr10plus"], ["hdr"], ["sdr"]
    public final List<String> extras; // "imax", "imax_enhanced", "3d", "seadex"
    public final List<String> audio;  // ["atmos"], ["ddp"]
    public final String channels;     // "5.1"
    public final String network;
    public final boolean cached;
    public final boolean p2p;
    public final String debrid;       // "TorBox"
    public final String provider;     // the addon's source line, e.g. "StremThru Torz"
    public final String group;
    public final String size;
    public final String bitrate;
    public final String languages;
    public final String filename;
    /** The addon's own text, readable (when it isn't key: value lines). */
    public final String details;

    private StreamInfo(Stream stream) {
        String name = normalise(stream.name);
        String description = normalise(stream.title);
        String file = stream.filename == null ? "" : stream.filename;
        Map<String, String> fields = structuredFields(stream.title);
        String text = name + "\n" + description + "\n" + file.replace(".", " ");
        String raw = stream.name + "\n" + stream.title;

        String res = fields.containsKey("res") ? resolution(fields.get("res")) : resolution(name);
        if (res.isEmpty()) res = resolution(text);
        if (res.isEmpty()) res = fallbackResolution(stream.quality.resolution);
        resolution = res;
        release = release(fields.containsKey("src") ? fields.get("src")
                : name + "\n" + file.replace(".", " ") + "\n" + description);
        visual = visual(fields.containsKey("vis") ? fields.get("vis") : text);
        extras = extras(fields.containsKey("vis") ? fields.get("vis") : text);
        audio = mainAudio(fields.containsKey("aud") ? fields.get("aud") : text);
        channels = channels(fields.containsKey("ch") ? fields.get("ch") : text);
        network = fields.containsKey("net") ? network(fields.get("net"), "") : network(text, file);
        cached = fields.containsKey("cached")
                ? Arrays.asList("yes", "true", "1").contains(fields.get("cached").toLowerCase(Locale.ROOT))
                : CACHED_TEXT.matcher(raw).find() || fields.getOrDefault("svc", "").contains("+");
        p2p = stream.kind() == Stream.KIND_TORRENT
                || "p2p".equalsIgnoreCase(fields.getOrDefault("type", ""));
        debrid = debrid(fields.containsKey("svc") ? "[" + fields.get("svc") + "]" : raw);
        provider = fields.containsKey("prov") ? fields.get("prov") : provider(description);
        group = fields.containsKey("grp") ? fields.get("grp") : group(file, stream.bingeGroup);
        // The size as the addon writes it (it may count GB in 1000s), else from the byte count.
        String sz = fields.containsKey("size") ? fields.get("size") : sizeText(description);
        size = !sz.isEmpty() ? sz : stream.quality.size > 0 ? Stream.formatSize(stream.quality.size) : "";
        Matcher br = BITRATE.matcher(fields.containsKey("br") ? fields.get("br") : description);
        bitrate = br.find() ? br.group(1) + " " + br.group(2).toUpperCase(Locale.ROOT) + "bps" : "";
        languages = fields.containsKey("lang") ? fields.get("lang") : languages(description);
        filename = file;
        details = fields.isEmpty() ? description : "";
    }

    public static StreamInfo of(Stream stream) {
        return new StreamInfo(stream);
    }

    /**
     * Badge keys in display order, e.g. ["dl_cached", "res_4k", "vis_dv"]: download, resolution,
     * release, visual, extras, audio, channels, network.
     */
    public List<String> badges() {
        List<String> out = new ArrayList<>();
        add(out, "dl", cached ? "cached" : p2p ? "p2p" : "");
        add(out, "res", resolution);
        add(out, "rel", release);
        for (String v : visual) add(out, "vis", v);
        for (String e : extras) add(out, "extra", e);
        for (String a : audio) add(out, "aud", a);
        add(out, "ch", channels.replace(".", ""));
        add(out, "net", network);
        return out;
    }

    private static void add(List<String> out, String slot, String key) {
        if (!key.isEmpty()) out.add(slot + "_" + key);
    }

    /** Every audio format mentioned, for the card's text line ("Atmos | DD+"). */
    public static List<String> audioCodecs(String text) {
        List<String> out = new ArrayList<>();
        for (String[] a : AUDIO) if (find(a[1], text)) out.add(LABELS.get(a[0]));
        return out;
    }

    /** {key: value} from key: value lines, if the text is in that format (3+ known keys). */
    static Map<String, String> structuredFields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : (text == null ? "" : text).split("\\r?\\n")) {
            Matcher m = KEY_LINE.matcher(line);
            if (m.matches() && STRUCTURED_KEYS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                fields.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
            }
        }
        return fields.size() >= 3 ? fields : new LinkedHashMap<>();
    }

    private static String resolution(String text) {
        for (String[] r : RES_TEXT) if (find(r[1], text)) return r[0];
        return "";
    }

    private static String fallbackResolution(int height) {
        if (height >= 2160) return "4k";
        if (height >= 1440) return "1440p";
        if (height >= 1080) return "1080p";
        if (height >= 720) return "720p";
        return height > 0 ? "sd" : "";
    }

    private static String release(String text) {
        for (String[] r : RELEASES) if (find(r[1], text)) return r[0];
        return "";
    }

    private static List<String> visual(String text) {
        List<String> found = new ArrayList<>();
        if (find("dv|dovi|dolby[ .]?vision", text)) found.add("dv");
        if (find("hdr10\\+|hdr10plus|hdr10p", text)) found.add("hdr10plus");
        else if (find("hdr10", text)) found.add("hdr10");
        else if (find("hdr", text)) found.add("hdr");
        if (found.isEmpty() && find("sdr", text)) found.add("sdr");
        return found;
    }

    private static List<String> extras(String text) {
        List<String> found = new ArrayList<>();
        if (find("imax[ .-]?enhanced", text)) found.add("imax_enhanced");
        else if (find("imax", text)) found.add("imax");
        if (find("3d|sbs|half-?ou", text)) found.add("3d");
        if (find("seadex|best[ .-]?release", text)) found.add("seadex");
        return found;
    }

    /** The main audio badge: Atmos when present (its codec is implied), else the best codec. */
    private static List<String> mainAudio(String text) {
        Set<String> found = new HashSet<>();
        for (String[] a : AUDIO) if (find(a[1], text)) found.add(a[0]);
        for (String key : AUDIO_RANK) if (found.contains(key)) return Arrays.asList(key);
        return new ArrayList<>();
    }

    private static String channels(String text) {
        Matcher m = CHANNELS.matcher(text);
        return m.find() ? m.group(1) + "." + m.group(2) : "";
    }

    private static String network(String text, String filename) {
        Set<String> tokens = new HashSet<>(Arrays.asList(filename.split("[\\s._\\-\\[\\]()]+")));
        for (String[][] n : NETWORKS) {
            for (String tag : n[1]) if (tokens.contains(tag)) return n[0][0];
            for (String name : n[2]) if (find(Pattern.quote(name), text)) return n[0][0];
        }
        return "";
    }

    private static String debrid(String text) {
        Matcher m = DEBRID_CODE.matcher(text);
        if (m.find()) return DEBRID.containsKey(m.group(1)) ? DEBRID.get(m.group(1)) : m.group(1);
        for (String name : DEBRID.values()) if (find(Pattern.quote(name), text)) return name;
        return "";
    }

    private static String group(String filename, String bingeGroup) {
        Matcher m = GROUP.matcher(filename);
        if (m.find() && !m.group(1).matches("\\d+")) return m.group(1);
        String[] parts = (bingeGroup == null ? "" : bingeGroup).split("\\|");
        String tail = parts.length == 0 ? "" : parts[parts.length - 1];
        return !tail.isEmpty() && !Pattern.compile("\\d{3,4}p|web|bluray|remux|\\.", Pattern.CASE_INSENSITIVE)
                .matcher(tail).find() ? tail : "";
    }

    private static String sizeText(String description) {
        Matcher m = SIZE_TEXT.matcher(description);
        return m.find() ? m.group(1) + " " + m.group(2).toUpperCase(Locale.ROOT) : "";
    }

    /** The addon's "where from" line, e.g. "[TB] StremThru Torz · BYNDR" -> "StremThru Torz". */
    private static String provider(String description) {
        for (String line : description.split("\n")) {
            if (DEBRID_CODE.matcher(line).find()) {
                String rest = strip(DEBRID_CODE.matcher(line).replaceAll("").trim(), " ·");
                return rest.split(" · ")[0].trim();
            }
        }
        return "";
    }

    private static String languages(String description) {
        Pattern sub = Pattern.compile("\\bsub\\b|\\(\\w{2}\\)", Pattern.CASE_INSENSITIVE);
        for (String line : description.split("\n")) {
            if (sub.matcher(line).find() || line.matches("(?i)[a-z]{2}(?: · [a-z]{2})*")) {
                if (!line.contains(" · ")) return line.split(" ")[0];
                List<String> codes = new ArrayList<>();
                for (String p : line.split(" · ")) if (p.matches("(?i)[a-z]{2,3}")) codes.add(p);
                return String.join(" · ", codes);
            }
        }
        return "";
    }
}
