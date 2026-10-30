package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.RequestLog;
import com.shiggsy365.bingetvredux.stremio.ResponseCache;
import com.shiggsy365.bingetvredux.stremio.StremioClient;

import java.io.File;

/**
 * Each profile's addon store (its addons, catalogs, hubs and their order), and the app's addon
 * client (with its cache of addon answers, shared by every profile).
 */
public final class Stremio {
    private static final int TIMEOUT_MS = 15_000;
    // Before addons were per profile, everyone's were here; each profile starts from a copy.
    private static final String SHARED_FILE = "addons.json";
    private static final java.util.Map<String, AddonStore> sAddons = new java.util.HashMap<>();
    private static StremioClient sClient;

    private Stremio() {}

    /** Sends every addon and MDBList request to logcat (tag "Net"), for measuring. */
    static void logRequests() {
        RequestLog.setSink((method, url, status, bytes, ms) -> Log.d("Net", method + " " + status
                + " " + bytes + "B " + ms + "ms [" + Thread.currentThread().getName() + "] " + url));
    }

    /** The addons of the profile in use. */
    public static synchronized AddonStore addons(Context context) {
        return addons(context, Profiles.current(context).id);
    }

    public static synchronized AddonStore addons(Context context, String profileId) {
        AddonStore store = sAddons.get(profileId);
        if (store == null) {
            File file = file(context, profileId);
            File shared = new File(context.getApplicationContext().getFilesDir(), SHARED_FILE);
            if (!file.exists() && shared.exists()) copy(shared, file);
            store = new AddonStore(file);
            sAddons.put(profileId, store);
        }
        return store;
    }

    static File file(Context context, String profileId) {
        return new File(context.getApplicationContext().getFilesDir(), "addons_" + profileId + ".json");
    }

    /** A new profile starts with a copy of another's addons and catalogs. */
    static synchronized void copyAddons(Context context, String fromProfile, String toProfile) {
        File from = file(context, fromProfile);
        if (!from.exists()) addons(context, fromProfile);
        if (from.exists()) copy(from, file(context, toProfile));
        sAddons.remove(toProfile);
    }

    /** A removed profile's addons go too. */
    static synchronized void deleteAddons(Context context, String profileId) {
        sAddons.remove(profileId);
        //noinspection ResultOfMethodCallIgnored
        file(context, profileId).delete();
    }

    /** After a restore: every profile's store is read again from its file. */
    static synchronized void reloadAddons() {
        sAddons.clear();
    }

    private static void copy(File from, File to) {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (java.io.IOException e) {
            Log.w("Stremio", "Couldn't copy " + from.getName() + ": " + e.getMessage());
        }
    }

    public static synchronized StremioClient client(Context context) {
        if (sClient == null) {
            logRequests();
            sClient = new StremioClient(TIMEOUT_MS, new ResponseCache(
                    new File(context.getApplicationContext().getCacheDir(), "stremio")));
        }
        return sClient;
    }
}
