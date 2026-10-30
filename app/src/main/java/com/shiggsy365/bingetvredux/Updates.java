package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.shiggsy365.bingetvredux.stremio.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * New versions from GitHub Releases: about once a week Home asks for the latest release, and if
 * it's newer offers to download and install it (once per version). Settings can check now.
 * Debug builds never check: a release can't install over them.
 */
public final class Updates {
    private static final String TAG = "Updates";
    private static final String LATEST =
            "https://api.github.com/repos/shiggsy365/BingeTV-Redux/releases/latest";
    private static final long CHECK_EVERY_MS = 7 * 24 * 3600_000L;
    private static final int TIMEOUT_MS = 15_000;
    private static final Background.Tasks sExecutor = new Background.Tasks(1);

    private Updates() {}

    /** A newer release: its version, notes and APK. */
    static final class Release {
        final String version;
        final String notes;
        final String apkUrl;

        Release(String version, String notes, String apkUrl) {
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    /** From Home: checks if a week has passed, and offers a version only once. */
    static void checkIfDue(Activity activity) {
        if (BuildConfig.DEBUG) return;
        SharedPreferences prefs = prefs(activity);
        if (System.currentTimeMillis() - prefs.getLong("checkedAt", 0) < CHECK_EVERY_MS) return;
        check(activity, false);
    }

    /** Checks now; {@code always} also says when there's nothing new, and re-offers. */
    static void check(Activity activity, boolean always) {
        sExecutor.execute(() -> {
            Release release = null;
            String error = null;
            try {
                release = latest();
                prefs(activity).edit().putLong("checkedAt", System.currentTimeMillis()).apply();
            } catch (IOException | org.json.JSONException e) {
                error = e.getMessage();
                Log.w(TAG, "Update check failed: " + error);
            }
            Release found = release;
            String message = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (found == null) {
                    if (always) Toast.makeText(activity, message != null ? "Couldn't check: " + message
                            : "You have the latest version (" + BuildConfig.VERSION_NAME + ")",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (!always && found.version.equals(prefs(activity).getString("offered", ""))) return;
                prefs(activity).edit().putString("offered", found.version).apply();
                offer(activity, found);
            });
        });
    }

    /** The latest release if it's newer than this app and has an APK; else null. */
    private static Release latest() throws IOException, org.json.JSONException {
        JSONObject data = new JSONObject(get(LATEST));
        String version = data.optString("tag_name").replaceFirst("^v", "");
        if (compare(version, BuildConfig.VERSION_NAME) <= 0) return null;
        String apk = apkFor(data.optJSONArray("assets"), Build.SUPPORTED_ABIS);
        return apk == null ? null : new Release(version, data.optString("body"), apk);
    }

    /**
     * The download for this device: the APK for its ABI (best first), else the one for all
     * ("BingeTV-Redux-1.0.5.apk" beside "…-armeabi-v7a.apk"), else any APK.
     */
    static String apkFor(JSONArray assets, String[] abis) throws org.json.JSONException {
        if (assets == null) return null;
        String universal = null, any = null;
        for (String abi : abis) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name").endsWith("-" + abi + ".apk")) {
                    return asset.getString("browser_download_url");
                }
            }
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name");
            if (!name.endsWith(".apk")) continue;
            if (any == null) any = asset.getString("browser_download_url");
            if (universal == null && !name.matches(".*-(armeabi|arm64|x86).*")) {
                universal = asset.getString("browser_download_url");
            }
        }
        return universal != null ? universal : any;
    }

    /** Compares dotted versions: "1.10.0" is newer than "1.9.2". */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void offer(Activity activity, Release release) {
        String notes = release.notes.length() > 600 ? release.notes.substring(0, 600) + "…" : release.notes;
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("BingeTV Redux " + release.version + " is available")
                .setMessage(notes.isEmpty() ? "Update now?" : notes)
                .setPositiveButton("Update", (d, w) -> download(activity, release))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void download(Activity activity, Release release) {
        Toast.makeText(activity, "Downloading BingeTV Redux " + release.version + "…",
                Toast.LENGTH_LONG).show();
        sExecutor.execute(() -> {
            File dir = new File(activity.getCacheDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File apk = new File(dir, "BingeTV-Redux.apk");
            try {
                try (Http.Body body = Http.get(release.apkUrl).timeouts(TIMEOUT_MS, 60_000).open();
                     OutputStream out = new FileOutputStream(apk)) {
                    if (!body.ok()) throw new IOException("GitHub answered HTTP " + body.status);
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = body.in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                activity.runOnUiThread(() -> install(activity, apk));
            } catch (IOException e) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "The download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void install(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", apk);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, "This device can't install the update from here",
                    Toast.LENGTH_LONG).show();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow BingeTV Redux to install apps when asked",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String get(String url) throws IOException {
        Http.Response r = Http.get(url).timeout(TIMEOUT_MS)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BingeTVRedux/" + BuildConfig.VERSION_NAME).send();
        if (r.status == 404) return "{}";  // no releases yet
        if (r.status != 200) throw new IOException("GitHub answered HTTP " + r.status);
        return r.text();
    }
}
