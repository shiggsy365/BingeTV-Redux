package com.shiggsy365.bingetvredux.stremio;

/**
 * Where outgoing requests are reported (the app sends them to logcat), so their number, size and
 * time can be measured. Keys in query strings are masked before they're reported.
 */
public final class RequestLog {
    public interface Sink {
        void request(String method, String url, String status, long bytes, long ms);
    }

    private static volatile Sink sSink;

    private RequestLog() {}

    public static void setSink(Sink sink) {
        sSink = sink;
    }

    /** {@code status} is the HTTP status, "cache" for a cached answer, or "error". */
    public static void log(String method, String url, String status, long bytes, long started) {
        Sink sink = sSink;
        if (sink == null) return;
        sink.request(method, mask(url), status, bytes, System.currentTimeMillis() - started);
    }

    static String mask(String url) {
        return url.replaceAll("(?i)((?:api_?key|key|token)=)[^&]*", "$1***");
    }
}
