package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Addon answers by URL, each until its own expiry: the most recent in memory, and all of them
 * on disk (when given a folder) so rows appear straight away after a restart. Expired entries
 * are kept, so an answer can still be shown when the addon is down. Files untouched for a week
 * are deleted.
 */
public final class ResponseCache {
    private static final int MEMORY_ENTRIES = 150;
    private static final long DISK_MAX_AGE_MS = 7 * 24 * 3600_000L;

    /** A cached answer, and whether it's still fresh. */
    public static final class Hit {
        public final JSONObject data;
        public final boolean fresh;

        Hit(JSONObject data, boolean fresh) {
            this.data = data;
            this.fresh = fresh;
        }
    }

    private static final class Cached {
        final JSONObject data;
        final long expires;

        Cached(JSONObject data, long expires) {
            this.data = data;
            this.expires = expires;
        }
    }

    private final File mDir;
    private final Map<String, Cached> mMemory =
            new LinkedHashMap<String, Cached>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > MEMORY_ENTRIES;
                }
            };

    /** {@code dir} may be null for a memory-only cache. */
    public ResponseCache(File dir) {
        mDir = dir;
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            prune();
        }
    }

    public synchronized Hit get(String url, long now) {
        Cached e = mMemory.get(url);
        if (e == null && mDir != null) {
            e = readDisk(url);
            if (e != null) mMemory.put(url, e);
        }
        return e == null ? null : new Hit(e.data, now < e.expires);
    }

    public synchronized void put(String url, JSONObject data, long expires) {
        Cached e = new Cached(data, expires);
        mMemory.put(url, e);
        if (mDir == null) return;
        try (OutputStream out = new FileOutputStream(file(url))) {
            out.write(new JSONObject().put("expires", expires).put("data", data).toString()
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
            // The memory copy still works.
        }
    }

    public synchronized void clear() {
        mMemory.clear();
        File[] files = mDir == null ? null : mDir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private Cached readDisk(String url) {
        File f = file(url);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            JSONObject o = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return new Cached(o.getJSONObject("data"), o.getLong("expires"));
        } catch (IOException | JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    private void prune() {
        File[] files = mDir.listFiles();
        long cutoff = System.currentTimeMillis() - DISK_MAX_AGE_MS;
        if (files == null) return;
        for (File f : files) {
            if (f.lastModified() < cutoff) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    private File file(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
            return new File(mDir, sb + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
