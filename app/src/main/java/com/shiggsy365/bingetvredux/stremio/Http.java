package com.shiggsy365.bingetvredux.stremio;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The app's HTTP requests, all made the same way: timeouts, the app's user agent, gzip,
 * redirects and a line in the request log ("Net" in logcat). For example
 * {@code Http.get(url).header("Accept", "application/json").send().text()}.
 */
public final class Http {
    public static final String USER_AGENT = "BingeTVRedux/1.0";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;

    private Http() {}

    public static Call get(String url) {
        return new Call("GET", url);
    }

    public static Call request(String method, String url) {
        return new Call(method, url);
    }

    /** A request to make: {@link #send} reads the whole answer, {@link #open} streams it. */
    public static final class Call {
        private final String mMethod;
        private final String mUrl;
        private final Map<String, String> mHeaders = new LinkedHashMap<>();
        private byte[] mBody;
        private int mConnectMs = DEFAULT_TIMEOUT_MS;
        private int mReadMs = DEFAULT_TIMEOUT_MS;
        private long mMaxBytes = Long.MAX_VALUE;

        Call(String method, String url) {
            mMethod = method;
            mUrl = url;
            mHeaders.put("User-Agent", USER_AGENT);
        }

        public Call header(String name, String value) {
            mHeaders.put(name, value);
            return this;
        }

        public Call timeout(int ms) {
            return timeouts(ms, ms);
        }

        public Call timeouts(int connectMs, int readMs) {
            mConnectMs = connectMs;
            mReadMs = readMs;
            return this;
        }

        /** Answers longer than this fail (as an IOException). */
        public Call maxBytes(long bytes) {
            mMaxBytes = bytes;
            return this;
        }

        public Call body(String contentType, byte[] body) {
            mHeaders.put("Content-Type", contentType);
            mBody = body;
            return this;
        }

        public Call json(String json) {
            return body("application/json", json.getBytes(StandardCharsets.UTF_8));
        }

        /** Makes the request and reads the answer, whatever its status. */
        public Response send() throws IOException {
            try (Body body = open()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = body.in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (out.size() > mMaxBytes) throw new IOException("answer too large");
                }
                body.bytes = out.size();
                return new Response(body.status, body.contentType, out.toByteArray());
            }
        }

        /** Makes the request; the caller reads the answer and closes it. */
        public Body open() throws IOException {
            long started = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) new URL(mUrl).openConnection();
            try {
                conn.setConnectTimeout(mConnectMs);
                conn.setReadTimeout(mReadMs);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod(mMethod);
                // Not set by hand: Android's connection then asks for gzip and unpacks it itself.
                for (Map.Entry<String, String> h : mHeaders.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
                if (mBody != null) {
                    conn.setDoOutput(true);
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(mBody);
                    }
                }
                int status = conn.getResponseCode();
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (in == null) in = new java.io.ByteArrayInputStream(new byte[0]);
                if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
                return new Body(this, conn, status, conn.getContentType(), in, started);
            } catch (IOException | RuntimeException e) {
                conn.disconnect();
                RequestLog.log(mMethod, mUrl, "error", 0, started);
                throw e;
            }
        }
    }

    /** A whole answer. */
    public static final class Response {
        public final int status;
        /** "" when the server didn't say. */
        public final String contentType;
        public final byte[] bytes;

        Response(int status, String contentType, byte[] bytes) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.bytes = bytes;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** An answer being read; closing it ends the request (and logs it). */
    public static final class Body implements Closeable {
        private final Call mCall;
        private final HttpURLConnection mConn;
        private final long mStarted;
        public final int status;
        /** "" when the server didn't say. */
        public final String contentType;
        public final InputStream in;
        long bytes = -1;

        Body(Call call, HttpURLConnection conn, int status, String contentType, InputStream in,
                long started) {
            mCall = call;
            mConn = conn;
            mStarted = started;
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.in = in;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The host, for messages that mustn't show the whole URL (it can hold passwords). */
        public String host() {
            return mConn.getURL().getHost();
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
            mConn.disconnect();
            RequestLog.log(mCall.mMethod, mCall.mUrl, String.valueOf(status), bytes, mStarted);
        }
    }
}
