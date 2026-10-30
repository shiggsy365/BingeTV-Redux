package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The people who use the app. Each profile has its own watch history, playback and subtitle
 * settings, MDBList account, addons and catalogs; live TV is shared. There's always at
 * least one profile; with more than one, the app asks who's watching when it starts.
 */
public final class Profiles {
    private static final String PREFS = "profiles";
    private static final String PREF_LIST = "list";
    private static final String PREF_CURRENT = "current";
    public static final String DEFAULT_ID = "p1";

    public static final class Profile {
        public final String id;
        public final String name;

        Profile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Profiles() {}

    /** A profile's picture (uploaded on the web page); it may not exist. */
    public static java.io.File avatarFile(Context context, String id) {
        return new java.io.File(new java.io.File(context.getApplicationContext().getFilesDir(), "avatars"),
                id + ".png");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Profile> all(Context context) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(context).getString(PREF_LIST, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject p = list.getJSONObject(i);
                out.add(new Profile(p.getString("id"), p.getString("name")));
            }
        } catch (JSONException ignored) {
            // Falls back to the default profile below.
        }
        if (out.isEmpty()) out.add(new Profile(DEFAULT_ID, "Me"));
        return out;
    }

    public static synchronized Profile get(Context context, String id) {
        for (Profile p : all(context)) if (p.id.equals(id)) return p;
        return null;
    }

    /** The profile in use (the first one until another is chosen). */
    public static synchronized Profile current(Context context) {
        Profile p = get(context, prefs(context).getString(PREF_CURRENT, DEFAULT_ID));
        return p != null ? p : all(context).get(0);
    }

    public static synchronized void setCurrent(Context context, String id) {
        prefs(context).edit().putString(PREF_CURRENT, id).apply();
        WatchStore.reset();
    }

    public static synchronized Profile add(Context context, String name) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("A profile needs a name");
        List<Profile> list = all(context);
        int n = 1;
        while (get(context, "p" + n) != null) n++;
        Profile profile = new Profile("p" + n, name);
        // Its own addons and catalogs, starting as a copy of the profile in use.
        Stremio.copyAddons(context, current(context).id, profile.id);
        list.add(profile);
        save(context, list);
        return profile;
    }

    public static synchronized void rename(Context context, String id, String name) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("A profile needs a name");
        List<Profile> list = all(context);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) list.set(i, new Profile(id, name));
        }
        save(context, list);
    }

    /** Removes a profile and its history and settings. The last one can't be removed. */
    public static synchronized void remove(Context context, String id) {
        List<Profile> list = all(context);
        if (list.size() <= 1) throw new IllegalArgumentException("There must be at least one profile");
        list.removeIf(p -> p.id.equals(id));
        save(context, list);
        WatchStore.delete(context, id);
        context.deleteSharedPreferences(Settings.prefsName(id));
        //noinspection ResultOfMethodCallIgnored
        avatarFile(context, id).delete();
        Stremio.deleteAddons(context, id);
        if (current(context).id.equals(id) || get(context, current(context).id) == null) {
            setCurrent(context, list.get(0).id);
        }
    }

    /** For backups: the profiles, their settings and watch history. */
    static JSONObject export(Context context) throws JSONException {
        JSONArray list = new JSONArray();
        for (Profile p : all(context)) {
            list.put(new JSONObject().put("id", p.id).put("name", p.name)
                    .put("settings", Settings.toJson(context, p.id))
                    .put("recentChannels", Settings.recentChannelsText(context, p.id))
                    .put("watch", WatchStore.export(context, p.id)));
        }
        return new JSONObject().put("profiles", list).put("current", current(context).id);
    }

    /**
     * From a backup: replaces every profile. A backup made without passwords has no MDBList
     * keys; a profile here already keeps its own, as the TV's password is kept.
     */
    static synchronized void restore(Context context, JSONObject data) throws JSONException {
        JSONArray list = data.getJSONArray("profiles");
        List<Profile> profiles = new ArrayList<>();
        java.util.Map<String, String> keys = new java.util.HashMap<>();
        for (Profile old : all(context)) {
            keys.put(old.id, Settings.string(context, old.id, Settings.MDBLIST_KEY));
            WatchStore.delete(context, old.id);
            context.deleteSharedPreferences(Settings.prefsName(old.id));
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.getJSONObject(i);
            String id = p.getString("id");
            profiles.add(new Profile(id, p.getString("name")));
            JSONObject settings = p.optJSONObject("settings");
            if (settings != null) Settings.apply(context, id, settings);
            String key = keys.get(id);
            if ((settings == null || !settings.has(Settings.MDBLIST_KEY)) && key != null && !key.isEmpty()) {
                Settings.putMdblistKey(context, id, key);
            }
            Settings.setRecentChannelsText(context, id, p.optString("recentChannels"));
            JSONArray watch = p.optJSONArray("watch");
            if (watch != null) WatchStore.restore(context, id, watch);
        }
        // Profiles here that the backup doesn't have go, with their addons.
        for (String id : keys.keySet()) {
            boolean kept = false;
            for (Profile p : profiles) kept |= p.id.equals(id);
            if (!kept) Stremio.deleteAddons(context, id);
        }
        save(context, profiles);
        setCurrent(context, data.optString("current", profiles.get(0).id));
    }

    private static void save(Context context, List<Profile> list) {
        JSONArray out = new JSONArray();
        try {
            for (Profile p : list) out.put(new JSONObject().put("id", p.id).put("name", p.name));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(context).edit().putString(PREF_LIST, out.toString()).apply();
    }
}
