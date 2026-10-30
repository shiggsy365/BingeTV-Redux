package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.RequestLog;
import com.shiggsy365.bingetvredux.stremio.ResponseCache;
import com.shiggsy365.bingetvredux.stremio.StremioClient;

import java.io.File;

/** The app's one addon store and addon client (with its cache of addon answers). */
public final class Stremio {
    private static final int TIMEOUT_MS = 15_000;
    private static AddonStore sAddons;
    private static StremioClient sClient;

    private Stremio() {}

    /** Sends every addon and MDBList request to logcat (tag "Net"), for measuring. */
    static void logRequests() {
        RequestLog.setSink((method, url, status, bytes, ms) -> Log.d("Net", method + " " + status
                + " " + bytes + "B " + ms + "ms [" + Thread.currentThread().getName() + "] " + url));
    }

    public static synchronized AddonStore addons(Context context) {
        if (sAddons == null) {
            sAddons = new AddonStore(new File(context.getApplicationContext().getFilesDir(),
                    "addons.json"));
        }
        return sAddons;
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
