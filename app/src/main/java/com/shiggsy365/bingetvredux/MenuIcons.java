package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.PathParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The menu bar's icons (Material icons, Apache 2.0), as 24×24 path data, so the TV and the web
 * page's hub icon chooser draw the same shapes. Hubs pick from {@link #HUB_ICONS}; the other
 * pages have their own.
 */
final class MenuIcons {
    static final String SEARCH = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z";
    static final String LIVE = "M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6H3c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V8c0,-1.11 -0.9,-2 -2,-2zM21,20H3V8h18v12zM9,10v8l7,-4z";

    /** The icons a hub can have, in the web page's order: key -> {label, path}. */
    static final Map<String, String[]> HUB_ICONS = new LinkedHashMap<>();
    static {
        icon("home", "Home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z");
        icon("movie", "Films", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z");
        icon("tv", "TV", "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z");
        icon("anime", "Anime", "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12L11.5,9.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z");
        icon("layers", "Collection", "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z");
        icon("kids", "Kids", "M9,11.75c-0.69,0 -1.25,0.56 -1.25,1.25s0.56,1.25 1.25,1.25 1.25,-0.56 1.25,-1.25 -0.56,-1.25 -1.25,-1.25zM15,11.75c-0.69,0 -1.25,0.56 -1.25,1.25s0.56,1.25 1.25,1.25 1.25,-0.56 1.25,-1.25 -0.56,-1.25 -1.25,-1.25zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8 0,-0.29 0.02,-0.58 0.05,-0.86 2.36,-1.05 4.23,-2.98 5.21,-5.37C11.07,8.33 14.05,10 17.42,10c0.78,0 1.53,-0.09 2.25,-0.26 0.21,0.71 0.33,1.47 0.33,2.26 0,4.41 -3.59,8 -8,8z");
        icon("sport", "Sport", "M19,5h-2V3H7v2H5C3.9,5 3,5.9 3,7v1c0,2.55 1.92,4.63 4.39,4.94 0.63,1.5 1.98,2.63 3.61,2.96V19H7v2h10v-2h-4v-3.1c1.63,-0.33 2.98,-1.46 3.61,-2.96C19.08,12.63 21,10.55 21,8V7C21,5.9 20.1,5 19,5zM5,8V7h2v3.82C5.84,10.4 5,9.3 5,8zM19,8c0,1.3 -0.84,2.4 -2,2.82V7h2V8z");
        icon("music", "Music", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z");
        icon("star", "Star", "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z");
        icon("heart", "Heart", "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z");
        icon("world", "World", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,19.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zM17.9,17.39c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z");
        icon("fire", "Trending", "M13.5,0.67s0.74,2.65 0.74,4.8c0,2.06 -1.35,3.73 -3.41,3.73 -2.07,0 -3.63,-1.67 -3.63,-3.73l0.03,-0.36C5.21,7.51 4,10.62 4,14c0,4.42 3.58,8 8,8s8,-3.58 8,-8C20,8.61 17.41,3.8 13.5,0.67zM11.71,19c-1.78,0 -3.22,-1.4 -3.22,-3.14 0,-1.62 1.05,-2.76 2.81,-3.12 1.77,-0.36 3.6,-1.21 4.62,-2.58 0.39,1.29 0.59,2.65 0.59,4.04 0,2.65 -2.15,4.8 -4.8,4.8z");
        icon("bookmark", "Bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z");
        icon("history", "Classics", "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z");
        icon("school", "Learning", "M5,13.18v4L12,21l7,-3.82v-4L12,17l-7,-3.82zM12,3L1,9l11,6 9,-4.91V17h2V9L12,3z");
        icon("film", "Cinema", "M18,3v2h-2L16,3L8,3v2L6,5L6,3L4,3v18h2v-2h2v2h8v-2h2v2h2L20,3h-2zM8,17L6,17v-2h2v2zM8,13L6,13v-2h2v2zM8,9L6,9L6,7h2v2zM18,17h-2v-2h2v2zM18,13h-2v-2h2v2zM18,9h-2L16,7h2v2z");
    }

    private MenuIcons() {}

    private static void icon(String key, String label, String path) {
        HUB_ICONS.put(key, new String[]{label, path});
    }

    /** A hub's icon when none has been chosen. */
    static String defaultFor(String hubId) {
        switch (hubId) {
            case "home": return "home";
            case "movies": return "movie";
            case "tvshows": return "tv";
            case "anime": return "anime";
            default: return "layers";
        }
    }

    /** The path data of a hub icon (the generic one for an unknown key). */
    static String hubPath(String key) {
        String[] icon = HUB_ICONS.get(key);
        return (icon != null ? icon : HUB_ICONS.get("layers"))[1];
    }

    /** For the web page: [{key, label, path}]. */
    static JSONArray toJson() throws JSONException {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String[]> e : HUB_ICONS.entrySet()) {
            out.put(new JSONObject().put("key", e.getKey()).put("label", e.getValue()[0])
                    .put("path", e.getValue()[1]));
        }
        return out;
    }

    /** An icon drawn from path data, {@code sizeDp} square, in white (tint it to recolour). */
    static Drawable drawable(Context context, String path, int sizeDp) {
        return new PathIcon(PathParser.createPathFromPathData(path),
                Math.round(sizeDp * context.getResources().getDisplayMetrics().density));
    }

    /** Draws a 24×24 path scaled to its bounds. */
    private static final class PathIcon extends Drawable {
        private final android.graphics.Path mPath;
        private final android.graphics.Paint mPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int mSize;

        PathIcon(android.graphics.Path path, int size) {
            mPath = path;
            mSize = size;
            mPaint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(b.width() / 24f, b.height() / 24f);
            canvas.drawPath(mPath, mPaint);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return mSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return mSize;
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
