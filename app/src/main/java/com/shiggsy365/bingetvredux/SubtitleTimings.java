package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * The timing of one subtitle on one version of a title, kept apart from the profile's settings:
 * two releases of an episode, or two subtitles of one release, are rarely off by the same amount.
 */
final class SubtitleTimings {
    private static final String PREFS = "subtitle_timings";
    private static final int MAX_ENTRIES = 500;

    static final class Saved {
        final long offsetMs;
        final double ratio;
        final boolean byHand;

        Saved(long offsetMs, double ratio, boolean byHand) {
            this.offsetMs = offsetMs;
            this.ratio = ratio;
            this.byHand = byHand;
        }
    }

    private SubtitleTimings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Saved get(Context context, String key) {
        String value = prefs(context).getString(key, null);
        if (value == null) return null;
        String[] parts = value.split(",");
        try {
            return new Saved(Long.parseLong(parts[0]), Double.parseDouble(parts[1]), "1".equals(parts[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void put(Context context, String key, long offsetMs, double ratio, boolean byHand) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString(key, offsetMs + "," + ratio + "," + (byHand ? 1 : 0) + "," + System.currentTimeMillis());
        Map<String, ?> all = prefs.getAll();
        if (all.size() >= MAX_ENTRIES) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                long at = savedAt(entry.getValue());
                if (at < oldestAt && !entry.getKey().equals(key)) {
                    oldestAt = at;
                    oldest = entry.getKey();
                }
            }
            if (oldest != null) edit.remove(oldest);
        }
        edit.apply();
    }

    private static long savedAt(Object value) {
        try {
            return Long.parseLong(String.valueOf(value).split(",")[3]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static void remove(Context context, String key) {
        prefs(context).edit().remove(key).apply();
    }
}
