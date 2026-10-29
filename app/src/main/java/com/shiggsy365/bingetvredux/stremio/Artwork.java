package com.shiggsy365.bingetvredux.stremio;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Artwork at about the size it's shown at. Addons often link TMDB's original images (a
 * background can be 3840x2160 and 1.4 MB) and TVDB's full-size ones: slow to download and
 * decode on a Fire TV Stick. TMDB serves every image at set widths; TVDB serves a 640 px copy
 * of each v4 artwork (its name ending "_t").
 */
public final class Artwork {
    public static final int BACKGROUND = 0, POSTER = 1, LOGO = 2, THUMBNAIL = 3;
    private static final int[] TMDB_WIDTH = {1280, 342, 500, 780};
    private static final Pattern TMDB =
            Pattern.compile("^(https?://image\\.tmdb\\.org/t/p/)(original|w\\d+)(/.+)$");
    private static final Pattern TVDB_V4 = Pattern.compile(
            "^(https?://artworks\\.thetvdb\\.com/banners/v4/.+?)(\\.(?:jpe?g|png))$",
            Pattern.CASE_INSENSITIVE);

    private Artwork() {}

    public static String sized(String url, int kind) {
        if (url == null || url.isEmpty()) return "";
        Matcher m = TMDB.matcher(url);
        if (m.matches()) {
            int width = TMDB_WIDTH[kind];
            String size = m.group(2);
            if (size.equals("original") || Integer.parseInt(size.substring(1)) > width) {
                return m.group(1) + "w" + width + m.group(3);
            }
            return url;
        }
        if (kind == THUMBNAIL) {
            m = TVDB_V4.matcher(url);
            if (m.matches() && !m.group(1).endsWith("_t")) return m.group(1) + "_t" + m.group(2);
        }
        return url;
    }
}
