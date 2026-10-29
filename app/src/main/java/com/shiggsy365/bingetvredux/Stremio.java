package com.shiggsy365.bingetvredux;

import android.content.Context;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.StremioClient;

import java.io.File;

/** The app's one addon store and addon client. */
public final class Stremio {
    private static AddonStore sAddons;
    private static final StremioClient sClient = new StremioClient();

    private Stremio() {}

    public static synchronized AddonStore addons(Context context) {
        if (sAddons == null) {
            sAddons = new AddonStore(new File(context.getApplicationContext().getFilesDir(),
                    "addons.json"));
        }
        return sAddons;
    }

    public static StremioClient client() {
        return sClient;
    }
}
