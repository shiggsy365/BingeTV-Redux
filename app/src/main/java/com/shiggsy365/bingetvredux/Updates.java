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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

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
        JSONArray assets = data.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.optString("name").endsWith(".apk")) {
                return new Release(version, data.optString("body"),
                        asset.getString("browser_download_url"));
            }
        }
        return null;
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
                HttpURLConnection conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(60_000);
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                } finally {
                    conn.disconnect();
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
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "BingeTVRedux/" + BuildConfig.VERSION_NAME);
            int status = conn.getResponseCode();
            if (status == 404) return "{}";  // no releases yet
            if (status != 200) throw new IOException("GitHub answered HTTP " + status);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
