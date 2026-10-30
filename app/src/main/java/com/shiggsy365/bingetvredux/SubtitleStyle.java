package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

/**
 * How subtitles look: colour, size and outline. Chosen from the player's subtitles menu and
 * remembered for the whole app (every profile and video).
 */
@OptIn(markerClass = UnstableApi.class)
final class SubtitleStyle {
    static final String[] COLOURS = {"White", "Yellow", "Cyan", "Green"};
    private static final int[] COLOUR_VALUES = {Color.WHITE, 0xFFFFEB3B, 0xFF4DD0E1, 0xFF69F0AE};
    static final String[] SIZES = {"Smaller", "Small", "Normal", "Large", "Extra large"};
    private static final float[] SIZE_VALUES = {0.036f, 0.045f, SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, 0.072f, 0.085f};
    static final String[] OUTLINES = {"Outline", "Drop shadow", "Dark box", "None"};

    private SubtitleStyle() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("subtitle_style", Context.MODE_PRIVATE);
    }

    /** For backups: the style, and putting one back. */
    static org.json.JSONObject toJson(Context context) throws org.json.JSONException {
        return new org.json.JSONObject().put("colour", colour(context)).put("size", size(context))
                .put("outline", outline(context));
    }

    static void restore(Context context, org.json.JSONObject style) {
        prefs(context).edit()
                .putInt("colour", Math.max(0, Math.min(COLOURS.length - 1, style.optInt("colour", 0))))
                .putInt("size2", Math.max(0, Math.min(SIZES.length - 1, style.optInt("size", 2))))
                .putInt("outline", Math.max(0, Math.min(OUTLINES.length - 1, style.optInt("outline", 0))))
                .apply();
    }

    static int colour(Context context) {
        return prefs(context).getInt("colour", 0);
    }

    static int size(Context context) {
        SharedPreferences p = prefs(context);
        // "size2" counts from Smaller; the first list ("size") started at Small.
        if (!p.contains("size2") && p.contains("size")) return p.getInt("size", 1) + 1;
        return p.getInt("size2", 2);
    }

    static int outline(Context context) {
        return prefs(context).getInt("outline", 0);
    }

    static void set(Context context, String key, int value) {
        prefs(context).edit().putInt(key.equals("size") ? "size2" : key, value).apply();
    }

    /** Draws {@code view}'s subtitles in the chosen style. */
    static void apply(SubtitleView view) {
        Context context = view.getContext();
        int outline = outline(context);
        int edge = outline == 0 ? CaptionStyleCompat.EDGE_TYPE_OUTLINE
                : outline == 1 ? CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW : CaptionStyleCompat.EDGE_TYPE_NONE;
        int background = outline == 2 ? 0xCC000000 : Color.TRANSPARENT;
        view.setApplyEmbeddedStyles(false);
        view.setStyle(new CaptionStyleCompat(COLOUR_VALUES[colour(context)], background, Color.TRANSPARENT,
                edge, Color.BLACK, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
        view.setFractionalTextSize(SIZE_VALUES[size(context)]);
    }
}
