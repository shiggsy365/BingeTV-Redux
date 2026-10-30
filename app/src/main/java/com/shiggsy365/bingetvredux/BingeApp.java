package com.shiggsy365.bingetvredux;

import android.app.ActivityManager;
import android.app.Application;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;

/** App-wide set-up before any screen opens. */
public class BingeApp extends Application {
    // Devices with less than this much memory (a Fire TV Stick has 1.5 GB) get lighter images.
    private static final long LOW_MEMORY_BYTES = 2_500L * 1024 * 1024;

    @Override
    public void onCreate() {
        super.onCreate();
        if (lowMemory()) {
            // Half the memory per picture. Glide keeps full colour for pictures with
            // transparency (logos), so only photos and posters lose a little colour depth.
            Glide.init(this, new GlideBuilder().setDefaultRequestOptions(
                    new RequestOptions().format(DecodeFormat.PREFER_RGB_565)));
        }
    }

    private boolean lowMemory() {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null) return false;
        if (am.isLowRamDevice()) return true;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.totalMem > 0 && info.totalMem < LOW_MEMORY_BYTES;
    }
}
