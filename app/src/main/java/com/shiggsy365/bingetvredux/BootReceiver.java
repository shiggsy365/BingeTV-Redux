package com.shiggsy365.bingetvredux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * After a reboot or an app update: starts the web server, runs a sync and keeps the periodic
 * sync scheduled. The sync is queued on WorkManager because it takes longer than a receiver
 * is allowed to run. Debug builds also start on {@link #ACTION_DEBUG_START}, so testing can
 * start the server without opening the app over whatever is on screen.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    static final String ACTION_DEBUG_START = "com.shiggsy365.bingetvredux.DEBUG_START";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || (BuildConfig.DEBUG && ACTION_DEBUG_START.equals(action))) {
            Context app = context.getApplicationContext();
            WebServerService.start(app);
            ChannelSyncWorker.syncNow(app);
            ChannelSyncWorker.schedule(app, ChannelSyncWorker.SYNC_INTERVAL_HOURS);
        }
    }
}
