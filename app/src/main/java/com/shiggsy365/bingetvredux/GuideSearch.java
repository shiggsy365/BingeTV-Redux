package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.MetaItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Search's "Live TV" row: programmes in the stored guide whose titles match. What's on now
 * comes first, then what's coming up, then what can still be watched on catch-up. OK plays
 * it live, plays the recording, or opens the guide at a programme still to come.
 */
final class GuideSearch {
    private static final String TAG = "GuideSearch";
    /** Ids of results: "guide:", the channel's id, then the programme's start and end. */
    static final String PREFIX = "guide:";
    private static final int LIMIT = 40;
    // Matches read from the store before they're sorted and cut to LIMIT.
    private static final int READ_MAX = 400;
    private static final long DAY_MS = 24 * 3600_000L;

    private GuideSearch() {}

    /** The matching programmes as cards. Off the main thread. */
    static List<MetaItem> find(Context context, String query) throws JSONException {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = GuideStore.get(context).getReadableDatabase();
        String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<MetaItem> out = new ArrayList<>();
        // On now and coming up, soonest first; then catch-up, most recent first.
        read(context, db, "p.title LIKE ? ESCAPE '\\' AND p.end_ms > ?", "p.start_ms",
                new String[] {like, String.valueOf(now)}, now, out);
        read(context, db, "p.title LIKE ? ESCAPE '\\' AND p.end_ms <= ? AND p.start_ms >= ?",
                "p.start_ms DESC", new String[] {like, String.valueOf(now),
                        String.valueOf(now - Catchup.MAX_DAYS * DAY_MS)}, now, out);
        return out;
    }

    /** Adds the programmes matching {@code where} (those still watchable) to {@code out}. */
    private static void read(Context context, SQLiteDatabase db, String where, String order,
            String[] args, long now, List<MetaItem> out) throws JSONException {
        if (out.size() >= LIMIT) return;
        try (Cursor c = db.rawQuery("SELECT p.stream_id, p.title, p.description, p.image, p.start_ms,"
                + " p.end_ms, c.name, c.archive FROM " + GuideStore.PROGRAMS + " p JOIN "
                + GuideStore.CHANNELS + " c ON c.stream_id = p.stream_id WHERE " + where
                + " ORDER BY " + order + " LIMIT " + READ_MAX, args)) {
            while (c.moveToNext() && out.size() < LIMIT) {
                long channel = c.getLong(0), start = c.getLong(4), end = c.getLong(5);
                if (end <= now && start < now - catchupDays(c.getString(7)) * DAY_MS) continue;
                File logo = GuideStore.logoFile(context, (int) channel);
                String logoUri = logo.exists() ? Uri.fromFile(logo).toString() : "";
                String image = c.isNull(3) ? "" : c.getString(3);
                MetaItem item = MetaItem.preview(new JSONObject()
                        .put("id", PREFIX + channel + ":" + start + ":" + end)
                        .put("type", "tv").put("name", c.getString(1))
                        .put("poster", image.isEmpty() ? logoUri : image).put("logo", logoUri)
                        .put("background", image).put("posterShape", "landscape")
                        .put("description", c.isNull(2) ? "" : c.getString(2)), null);
                if (item != null) out.add(item.forRow(c.getString(6) + "  ·  " + when(start, end, now), null));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not search the guide", e);
        }
    }

    /** "Now", "Today 21:00", "Tomorrow 06:30", "Catch-up · Mon 20:00". */
    static String when(long start, long end, long now) {
        if (start <= now && end > now) return "Now";
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(now);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long today = day.getTimeInMillis();
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(start));
        String date = start >= today && start < today + DAY_MS ? "Today"
                : start >= today + DAY_MS && start < today + 2 * DAY_MS ? "Tomorrow"
                : start >= today - DAY_MS && start < today ? "Yesterday"
                : new SimpleDateFormat("EEE", Locale.getDefault()).format(new Date(start));
        return (end <= now ? "Catch-up  ·  " : "") + date + " " + time;
    }

    private static int catchupDays(String archive) {
        int days = 0;
        if (archive == null) return 0;
        try {
            JSONArray list = new JSONArray(archive);
            for (int i = 0; i < list.length(); i++) days = Math.max(days, list.optInt(i));
        } catch (JSONException e) {
            // No catch-up.
        }
        return days;
    }

    /**
     * Opens a result: plays it live or on catch-up, or opens the guide at it. False for
     * anything that isn't one.
     */
    static boolean open(Activity activity, MetaItem item) {
        if (!item.id.startsWith(PREFIX)) return false;
        String[] parts = item.id.substring(PREFIX.length()).split(":");
        long channel = Long.parseLong(parts[0]), start = Long.parseLong(parts[1]), end = Long.parseLong(parts[2]);
        long now = System.currentTimeMillis();
        if (start > now) {
            activity.startActivity(new Intent(activity, GuideActivity.class)
                    .putExtra(GuideActivity.EXTRA_CHANNEL_ID, channel)
                    .putExtra(GuideActivity.EXTRA_TIME, start));
            return true;
        }
        Intent player = new Intent(activity, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_GROUP_ID, GuideData.ALL_GROUP_ID)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel);
        if (end <= now) {
            player.putExtra(PlayerActivity.EXTRA_CATCHUP_START, start)
                    .putExtra(PlayerActivity.EXTRA_CATCHUP_END, end);
        }
        // The player zaps through the whole guide, so it has to be loaded (as Home's On Now).
        if (GuideData.lastGroup(GuideData.ALL_GROUP_ID) != null) {
            activity.startActivity(player);
            return true;
        }
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            GuideData.load(app);
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed()) activity.startActivity(player);
            });
        }, "GuideSearch").start();
        return true;
    }
}
