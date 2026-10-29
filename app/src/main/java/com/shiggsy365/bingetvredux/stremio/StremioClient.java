package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Talks to Stremio addons over HTTP. Safe to share between threads. */
public class StremioClient {
    public static final String USER_AGENT = "BingeTVRedux/0.1";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    // Long-running shows' meta can be several MB; anything far bigger isn't an addon answer.
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private final int mTimeoutMs;
    private final ResponseCache mCache;

    public StremioClient() {
        this(DEFAULT_TIMEOUT_MS, null);
    }

    /** {@code cache} may be null: nothing is then cached. */
    public StremioClient(int timeoutMs, ResponseCache cache) {
        mTimeoutMs = timeoutMs;
        mCache = cache;
    }

    public ResponseCache cache() {
        return mCache;
    }

    /** A manifest and the transport URL it was fetched from. */
    public static final class Fetched {
        public final String transportUrl;
        public final Manifest manifest;

        Fetched(String transportUrl, Manifest manifest) {
            this.transportUrl = transportUrl;
            this.manifest = manifest;
        }
    }

    public Fetched fetchManifest(String url) throws StremioException {
        String transportUrl = StremioUrls.normalize(url);
        return new Fetched(transportUrl, Manifest.fromJson(getJson(transportUrl)));
    }

    public JSONObject resource(String transportUrl, String resource, String type, String id,
            List<Map.Entry<String, String>> extra) throws StremioException {
        return resource(transportUrl, resource, type, id, extra, 0);
    }

    /**
     * A resource, from the cache while it's younger than {@code ttlMs} (0: always asks the
     * addon). If the addon can't be reached, an expired cached answer is used instead.
     */
    public JSONObject resource(String transportUrl, String resource, String type, String id,
            List<Map.Entry<String, String>> extra, long ttlMs) throws StremioException {
        String url = StremioUrls.resourceUrl(transportUrl, resource, type, id, extra);
        if (mCache == null || ttlMs <= 0) return getJson(url);
        long now = System.currentTimeMillis();
        ResponseCache.Hit hit = mCache.get(url, now);
        if (hit != null && hit.fresh) return hit.data;
        try {
            JSONObject data = getJson(url);
            mCache.put(url, data, now + ttlMs);
            return data;
        } catch (StremioException e) {
            if (hit != null) return hit.data;
            throw e;
        }
    }

    /** A cached answer, fresh or not, without asking the addon; null if there's none. */
    public ResponseCache.Hit peek(String transportUrl, String resource, String type, String id,
            List<Map.Entry<String, String>> extra) {
        if (mCache == null) return null;
        return mCache.get(StremioUrls.resourceUrl(transportUrl, resource, type, id, extra),
                System.currentTimeMillis());
    }

    public JSONObject getJson(String url) throws StremioException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(mTimeoutMs);
            conn.setReadTimeout(mTimeoutMs);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int status = conn.getResponseCode();
            if (status != 200) throw new StremioException("The addon answered HTTP " + status);
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
            return new JSONObject(read(in));
        } catch (JSONException e) {
            throw new StremioException("The addon's answer isn't JSON");
        } catch (IOException e) {
            throw new StremioException("Couldn't reach the addon: " + describe(e), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("answer too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String describe(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) return "it took too long to answer";
        if (e instanceof java.net.UnknownHostException) return "unknown host " + e.getMessage();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
