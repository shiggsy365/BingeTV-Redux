package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;

import com.shiggsy365.bingetvredux.stremio.Streams;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Each profile's settings: streams, playback, subtitles, MDBList and trailers. Changed on the
 * web page (see SettingsApi); read here by the TV screens.
 */
public final class Settings {
    public static final String SORT = "sort";
    public static final String MAX_RESOLUTION = "maxResolution";
    public static final String HIDE_CAM = "hideCam";
    public static final String EXCLUDE = "exclude";
    /** What Play does: "auto" plays the best stream, "choose" lists them. */
    public static final String PLAY_BUTTON = "playButton";
    public static final String AUTOPLAY_NEXT = "autoplayNext";
    public static final String SUBTITLE_LANGUAGES = "subtitleLanguages";
    public static final String SUBTITLES_ON = "subtitlesOn";
    public static final String WATCHED_PERCENT = "watchedPercent";
    public static final String MDBLIST_KEY = "mdblistKey";
    public static final String MDBLIST_SCROBBLE = "mdblistScrobble";
    public static final String MDBLIST_SYNC = "mdblistSync";
    public static final String HOME_TRAILERS = "homeTrailers";
    public static final String TRAILER_DELAY = "trailerDelay";
    public static final String TRAILER_MUTED = "trailerMuted";
    public static final String ROW_CONTINUE = "rowContinue";
    public static final String ROW_NEXT_UP = "rowNextUp";
    public static final String ROW_WATCHLIST = "rowWatchlist";
    public static final String ROW_ON_NOW = "rowOnNow";

    /** Every setting with its default, which also gives its type. */
    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(SORT, Streams.SORT_QUALITY);
        DEFAULTS.put(MAX_RESOLUTION, 0);
        DEFAULTS.put(HIDE_CAM, true);
        DEFAULTS.put(EXCLUDE, "");
        DEFAULTS.put(PLAY_BUTTON, "auto");
        DEFAULTS.put(AUTOPLAY_NEXT, true);
        DEFAULTS.put(SUBTITLE_LANGUAGES, "");
        DEFAULTS.put(SUBTITLES_ON, false);
        DEFAULTS.put(WATCHED_PERCENT, 90);
        DEFAULTS.put(MDBLIST_KEY, "");
        DEFAULTS.put(MDBLIST_SCROBBLE, true);
        DEFAULTS.put(MDBLIST_SYNC, true);
        DEFAULTS.put(HOME_TRAILERS, false);
        DEFAULTS.put(TRAILER_DELAY, 4);
        DEFAULTS.put(TRAILER_MUTED, true);
        DEFAULTS.put(ROW_CONTINUE, true);
        DEFAULTS.put(ROW_NEXT_UP, true);
        DEFAULTS.put(ROW_WATCHLIST, true);
        DEFAULTS.put(ROW_ON_NOW, false);
    }

    private Settings() {}

    static String prefsName(String profileId) {
        return "settings_" + profileId;
    }

    private static SharedPreferences prefs(Context context, String profileId) {
        return context.getApplicationContext().getSharedPreferences(prefsName(profileId),
                Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefs(Context context) {
        return prefs(context, Profiles.current(context).id);
    }

    public static boolean bool(Context context, String key) {
        return prefs(context).getBoolean(key, (Boolean) DEFAULTS.get(key));
    }

    public static int integer(Context context, String key) {
        return prefs(context).getInt(key, (Integer) DEFAULTS.get(key));
    }

    public static String string(Context context, String key) {
        return prefs(context).getString(key, (String) DEFAULTS.get(key));
    }

    public static void putString(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    public static Streams.Prefs streamPrefs(Context context) {
        Streams.Prefs p = new Streams.Prefs();
        p.sort = integer(context, SORT);
        p.maxResolution = integer(context, MAX_RESOLUTION);
        p.hideCam = bool(context, HIDE_CAM);
        p.exclude = Streams.Prefs.parseKeywords(string(context, EXCLUDE));
        return p;
    }

    /**
     * Subtitle languages as ISO 639-2 codes, best first. Typed as codes or names ("en",
     * "English"); empty means the device's language.
     */
    public static List<String> subtitleLanguages(Context context) {
        List<String> out = new ArrayList<>();
        for (String part : string(context, SUBTITLE_LANGUAGES).split(",")) {
            String lang = part.trim();
            if (!lang.isEmpty()) out.add(lang.toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) out.add(Locale.getDefault().getISO3Language());
        return out;
    }

    /** A profile's settings for the web page. */
    public static JSONObject toJson(Context context, String profileId) throws JSONException {
        SharedPreferences p = prefs(context, profileId);
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> e : DEFAULTS.entrySet()) {
            Object def = e.getValue();
            String key = e.getKey();
            if (def instanceof Boolean) out.put(key, p.getBoolean(key, (Boolean) def));
            else if (def instanceof Integer) out.put(key, p.getInt(key, (Integer) def));
            else out.put(key, p.getString(key, (String) def));
        }
        return out;
    }

    /** Changes the settings in {@code values}; unknown keys and wrong types are ignored. */
    public static void apply(Context context, String profileId, JSONObject values) {
        SharedPreferences.Editor editor = prefs(context, profileId).edit();
        for (Map.Entry<String, Object> e : DEFAULTS.entrySet()) {
            String key = e.getKey();
            if (!values.has(key)) continue;
            Object def = e.getValue();
            if (def instanceof Boolean) editor.putBoolean(key, values.optBoolean(key));
            else if (def instanceof Integer) editor.putInt(key, values.optInt(key));
            else editor.putString(key, values.optString(key).trim());
        }
        editor.apply();
    }
}
