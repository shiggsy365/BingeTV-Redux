package com.shiggsy365.bingetvredux;

import android.content.Context;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.ResponseCache;
import com.shiggsy365.bingetvredux.stremio.StremioClient;

import java.io.File;

/** The app's one addon store and addon client (with its cache of addon answers). */
public final class Stremio {
    private static final int TIMEOUT_MS = 15_000;
    private static AddonStore sAddons;
    private static StremioClient sClient;

    private Stremio() {}

    public static synchronized AddonStore addons(Context context) {
        if (sAddons == null) {
            sAddons = new AddonStore(new File(context.getApplicationContext().getFilesDir(),
                    "addons.json"));
        }
        return sAddons;
    }

    public static synchronized StremioClient client(Context context) {
        if (sClient == null) {
            sClient = new StremioClient(TIMEOUT_MS, new ResponseCache(
                    new File(context.getApplicationContext().getCacheDir(), "stremio")));
        }
        return sClient;
    }
}
