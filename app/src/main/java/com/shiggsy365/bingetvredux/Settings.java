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
    /** Films' highest resolution (the one setting before episodes had their own). */
    public static final String MAX_RESOLUTION = "maxResolution";
    public static final String MAX_RESOLUTION_EPISODES = "maxResolutionEpisodes";
    /** Largest file in GB (0: any), for films and for episodes. */
    public static final String MAX_SIZE = "maxSizeGb";
    public static final String MAX_SIZE_EPISODES = "maxSizeEpisodesGb";
    /** Up Next appears this many seconds before the end of episodes under 30 minutes, 30–60, and longer. */
    public static final String UP_NEXT_SHORT = "upNextShort";
    public static final String UP_NEXT_MEDIUM = "upNextMedium";
    public static final String UP_NEXT_LONG = "upNextLong";
    public static final String HIDE_CAM = "hideCam";
    public static final String EXCLUDE = "exclude";
    /** What Play does: "auto" plays the best stream, "choose" lists them. */
    public static final String PLAY_BUTTON = "playButton";
    public static final String AUTOPLAY_NEXT = "autoplayNext";
    public static final String SUBTITLE_LANGUAGES = "subtitleLanguages";
    public static final String SUBTITLES_ON = "subtitlesOn";
    /** Audio track languages, best first (codes or names). */
    public static final String AUDIO_LANGUAGES = "audioLanguages";
    public static final String WATCHED_PERCENT = "watchedPercent";
    public static final String MDBLIST_KEY = "mdblistKey";
    public static final String MDBLIST_SCROBBLE = "mdblistScrobble";
    public static final String MDBLIST_SYNC = "mdblistSync";
    public static final String HOME_TRAILERS = "homeTrailers";
    public static final String TRAILER_DELAY = "trailerDelay";
    public static final String TRAILER_MUTED = "trailerMuted";
    public static final String ROW_CONTINUE = "rowContinue";
    public static final String ROW_NEXT_UP = "rowNextUp";
    /** Continue Watching also lists the next episode of shows being watched (one row, as BingeTV). */
    public static final String CONTINUE_WITH_NEXT_UP = "continueWithNextUp";
    /** Live TV on the menu bar and Home (off: films and series only). */
    public static final String LIVE_TV = "liveTv";
    public static final String ROW_WATCHLIST = "rowWatchlist";
    public static final String ROW_ON_NOW = "rowOnNow";
    /** Radio and Podcasts on the menu bar. */
    public static final String RADIO = "radio";
    public static final String PODCASTS_PAGE = "podcastsPage";
    /** Radio's country for its top stations (two letters; empty: the device's). */
    public static final String RADIO_COUNTRY = "radioCountry";
    /** A gPodder-compatible server (oPodSync, Nextcloud gPodder) that keeps podcasts in sync. */
    public static final String PODCAST_SYNC_URL = "podcastSyncUrl";
    public static final String PODCAST_SYNC_USER = "podcastSyncUser";
    public static final String PODCAST_SYNC_PASSWORD = "podcastSyncPassword";
    /** Podcast Index's API key and secret, for its directory (Apple's is used without). */
    public static final String PODCAST_INDEX_KEY = "podcastIndexKey";
    public static final String PODCAST_INDEX_SECRET = "podcastIndexSecret";
    /** Settings left out of backups made without passwords, and kept when one is restored. */
    static final String[] SECRETS = {Settings.MDBLIST_KEY, PODCAST_SYNC_PASSWORD, PODCAST_INDEX_SECRET};

    /** Every setting with its default, which also gives its type. */
    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(SORT, Streams.SORT_QUALITY);
        DEFAULTS.put(MAX_RESOLUTION, 0);
        DEFAULTS.put(MAX_RESOLUTION_EPISODES, 0);
        DEFAULTS.put(MAX_SIZE, 0);
        DEFAULTS.put(MAX_SIZE_EPISODES, 0);
        DEFAULTS.put(UP_NEXT_SHORT, 30);
        DEFAULTS.put(UP_NEXT_MEDIUM, 45);
        DEFAULTS.put(UP_NEXT_LONG, 60);
        DEFAULTS.put(HIDE_CAM, true);
        DEFAULTS.put(EXCLUDE, "");
        DEFAULTS.put(PLAY_BUTTON, "auto");
        DEFAULTS.put(AUTOPLAY_NEXT, true);
        DEFAULTS.put(SUBTITLE_LANGUAGES, "");
        DEFAULTS.put(SUBTITLES_ON, false);
        DEFAULTS.put(AUDIO_LANGUAGES, "");
        DEFAULTS.put(WATCHED_PERCENT, 90);
        DEFAULTS.put(MDBLIST_KEY, "");
        DEFAULTS.put(MDBLIST_SCROBBLE, true);
        DEFAULTS.put(MDBLIST_SYNC, true);
        DEFAULTS.put(HOME_TRAILERS, false);
        DEFAULTS.put(TRAILER_DELAY, 4);
        DEFAULTS.put(TRAILER_MUTED, true);
        DEFAULTS.put(ROW_CONTINUE, true);
        DEFAULTS.put(ROW_NEXT_UP, true);
        DEFAULTS.put(CONTINUE_WITH_NEXT_UP, true);
        DEFAULTS.put(LIVE_TV, true);
        DEFAULTS.put(ROW_WATCHLIST, true);
        DEFAULTS.put(ROW_ON_NOW, false);
        DEFAULTS.put(RADIO, true);
        DEFAULTS.put(PODCASTS_PAGE, true);
        DEFAULTS.put(RADIO_COUNTRY, "");
        DEFAULTS.put(PODCAST_SYNC_URL, "");
        DEFAULTS.put(PODCAST_SYNC_USER, "");
        DEFAULTS.put(PODCAST_SYNC_PASSWORD, "");
        DEFAULTS.put(PODCAST_INDEX_KEY, "");
        DEFAULTS.put(PODCAST_INDEX_SECRET, "");
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

    /** Another profile's setting (e.g. for syncing it while it isn't the one in use). */
    public static String string(Context context, String profileId, String key) {
        return prefs(context, profileId).getString(key, (String) DEFAULTS.get(key));
    }

    public static int integer(Context context, String profileId, String key) {
        return prefs(context, profileId).getInt(key, (Integer) DEFAULTS.get(key));
    }

    public static void putString(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    // Live channels the profile tuned to, most recent first, for Home's On Now row. Kept with
    // the settings (so it goes with the profile) but not one of them.
    private static final String RECENT_CHANNELS = "recentChannels";
    private static final int RECENT_CHANNELS_MAX = 30;

    public static List<Long> recentChannels(Context context) {
        List<Long> out = new ArrayList<>();
        for (String id : prefs(context).getString(RECENT_CHANNELS, "").split(",")) {
            try {
                if (!id.isEmpty()) out.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // Skip anything that isn't an id.
            }
        }
        return out;
    }

    /** For backups: a profile's recent channels as stored, and putting them back. */
    static String recentChannelsText(Context context, String profileId) {
        return prefs(context, profileId).getString(RECENT_CHANNELS, "");
    }

    static void setRecentChannelsText(Context context, String profileId, String text) {
        prefs(context, profileId).edit().putString(RECENT_CHANNELS, text).apply();
    }

    /** A profile's secret setting put back (a backup made without passwords leaves it out). */
    static void putSecret(Context context, String profileId, String key, String value) {
        prefs(context, profileId).edit().putString(key, value).apply();
    }

    // Lists kept with the profile (and in its backups) that aren't settings, as JSON text.
    static final String RECENT_SEARCHES = "recentSearches";
    static final String RADIO_FAVOURITES = "radioFavourites";
    static final String PODCASTS = "podcasts";
    static final String PODCAST_PROGRESS = "podcastProgress";
    static final String[] DATA = {RECENT_SEARCHES, RADIO_FAVOURITES, PODCASTS, PODCAST_PROGRESS};

    static String data(Context context, String key) {
        return data(context, Profiles.current(context).id, key);
    }

    static String data(Context context, String profileId, String key) {
        return prefs(context, profileId).getString(key, "");
    }

    static void putData(Context context, String key, String json) {
        putData(context, Profiles.current(context).id, key, json);
    }

    static void putData(Context context, String profileId, String key, String json) {
        prefs(context, profileId).edit().putString(key, json).apply();
    }

    public static void addRecentChannel(Context context, long channelId) {
        List<Long> ids = recentChannels(context);
        ids.remove(Long.valueOf(channelId));
        ids.add(0, channelId);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(ids.size(), RECENT_CHANNELS_MAX); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        prefs(context).edit().putString(RECENT_CHANNELS, sb.toString()).apply();
    }

    /** The profile's stream order and filters, for a film or an episode. */
    public static Streams.Prefs streamPrefs(Context context, boolean episode) {
        Streams.Prefs p = new Streams.Prefs();
        p.sort = integer(context, SORT);
        p.maxResolution = integer(context, episode ? MAX_RESOLUTION_EPISODES : MAX_RESOLUTION);
        p.maxSize = integer(context, episode ? MAX_SIZE_EPISODES : MAX_SIZE) * 1_000_000_000L;
        p.hideCam = bool(context, HIDE_CAM);
        p.exclude = Streams.Prefs.parseKeywords(string(context, EXCLUDE));
        return p;
    }

    /** How long before the end of an episode Up Next appears, for its length. */
    public static long upNextBeforeEndMs(Context context, long durationMs) {
        String key = durationMs < 30 * 60_000L ? UP_NEXT_SHORT
                : durationMs <= 60 * 60_000L ? UP_NEXT_MEDIUM : UP_NEXT_LONG;
        return integer(context, key) * 1000L;
    }

    /**
     * Subtitle languages as ISO 639-2 codes, best first. Typed as codes or names ("en",
     * "English"); empty means the device's language.
     */
    public static List<String> subtitleLanguages(Context context) {
        return languages(string(context, SUBTITLE_LANGUAGES));
    }

    /** Audio languages as ISO 639-2 codes, best first; empty means the device's language. */
    public static List<String> audioLanguages(Context context) {
        return languages(string(context, AUDIO_LANGUAGES));
    }

    /** "en, French, spa" as ["eng", "fra", "spa"]; the device's language when empty. */
    static List<String> languages(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("[,;/]")) {
            String lang = part.trim().toLowerCase(Locale.ROOT);
            if (lang.isEmpty()) continue;
            String code = code(lang);
            if (code != null && !out.contains(code)) out.add(code);
        }
        if (out.isEmpty()) out.add(Locale.getDefault().getISO3Language());
        return out;
    }

    private static String code(String lang) {
        if (lang.matches("[a-z]{2,3}")) {
            try {
                return new Locale(lang).getISO3Language();
            } catch (java.util.MissingResourceException e) {
                return lang;
            }
        }
        for (Locale l : Locale.getAvailableLocales()) {
            if (l.getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(lang)) {
                try {
                    return l.getISO3Language();
                } catch (java.util.MissingResourceException e) {
                    return null;
                }
            }
        }
        return null;
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
