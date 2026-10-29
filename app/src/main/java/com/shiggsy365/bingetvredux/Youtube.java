package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube trailers, as Stremio Bridge plays them: YouTube is asked for the video as its Apple
 * Vision Pro app (VISIONOS). Its streams come as plain URLs (nothing to decipher) and play to
 * the end, where other clients' are refused about a minute in. The video and audio come as
 * separate streams, which the player plays together.
 */
public final class Youtube {
    private static final String TAG = "Youtube";
    private static final String WATCH = "https://www.youtube.com/watch?v=aqz-KE-bpKQ";
    private static final String PLAYER = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=";
    private static final String WEB_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    public static final String AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private static final String CLIENT_VERSION = "1.02";
    // The API key and visitor id from a watch page are kept this long.
    private static final long CONFIG_FOR_MS = 6 * 3600_000L;
    private static final int TIMEOUT_MS = 10_000;
    private static final Pattern VIDEO_ID =
            Pattern.compile("(?:video_id=|youtu\\.be/|[?&]v=|/embed/|/shorts/)([\\w-]{11})");
    private static final Pattern CODECS = Pattern.compile("codecs=\"([^\"]+)\"");

    private Youtube() {}

    /** A trailer's video and audio streams. */
    public static final class Streams {
        public final String videoUrl;
        public final String audioUrl;
        public final int height;

        Streams(String videoUrl, String audioUrl, int height) {
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.height = height;
        }
    }

    public static final class YoutubeException extends Exception {
        YoutubeException(String message) {
            super(message);
        }
    }

    /** The YouTube id in a trailer reference (an id, or a YouTube URL), else "". */
    public static String videoId(String ref) {
        if (ref == null) return "";
        if (ref.matches("[\\w-]{11}")) return ref;
        Matcher m = VIDEO_ID.matcher(ref);
        return m.find() ? m.group(1) : "";
    }

    /** The streams for a video, up to {@code maxHeight}. Network: off the main thread. */
    public static Streams resolve(Context context, String videoId, int maxHeight)
            throws YoutubeException {
        long started = System.currentTimeMillis();
        try {
            JSONObject response = player(context, videoId, false);
            JSONObject status = response.optJSONObject("playabilityStatus");
            if (status != null && "LOGIN_REQUIRED".equals(status.optString("status"))) {
                response = player(context, videoId, true);  // the visitor id has gone stale
                status = response.optJSONObject("playabilityStatus");
            }
            if (status == null || !"OK".equals(status.optString("status"))) {
                throw new YoutubeException("YouTube won't play it: "
                        + (status == null ? "no answer" : status.optString("reason", status.optString("status"))));
            }
            JSONObject data = response.optJSONObject("streamingData");
            JSONArray formats = data == null ? null : data.optJSONArray("adaptiveFormats");
            if (formats == null) throw new YoutubeException("YouTube sent no streams");
            JSONObject video = best(formats, "video/", maxHeight, "avc1", "vp09", "vp9");
            JSONObject audio = best(formats, "audio/", Integer.MAX_VALUE, "mp4a", "opus");
            if (video == null || audio == null) throw new YoutubeException("No playable streams");
            Log.i(TAG, videoId + ": " + video.optInt("height") + "p " + codec(video) + " + "
                    + codec(audio) + " in " + (System.currentTimeMillis() - started) + " ms");
            return new Streams(video.getString("url"), audio.getString("url"), video.optInt("height"));
        } catch (IOException e) {
            throw new YoutubeException("YouTube didn't answer: " + e.getMessage());
        } catch (JSONException e) {
            throw new YoutubeException("YouTube's answer couldn't be read");
        }
    }

    /** H.264 (else VP9) video up to maxHeight, or the default audio track's AAC (else Opus). */
    private static JSONObject best(JSONArray formats, String kind, int maxHeight, String... codecs) {
        List<JSONObject> usable = new ArrayList<>();
        boolean anyDefault = false;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null || f.optString("url").isEmpty() || !f.optString("mimeType").startsWith(kind)
                    || f.optInt("height", 0) > maxHeight) {
                continue;
            }
            usable.add(f);
            JSONObject track = f.optJSONObject("audioTrack");
            if (track != null && track.optBoolean("audioIsDefault")) anyDefault = true;
        }
        if (anyDefault) {
            usable.removeIf(f -> {
                JSONObject track = f.optJSONObject("audioTrack");
                return track != null && !track.optBoolean("audioIsDefault");
            });
        }
        for (String codec : codecs) {
            JSONObject best = null;
            for (JSONObject f : usable) {
                if (!codec(f).startsWith(codec)) continue;
                if (best == null || score(f) > score(best)) best = f;
            }
            if (best != null) return best;
        }
        return null;
    }

    private static long score(JSONObject f) {
        return f.optInt("height") * 1_000_000_000L + f.optInt("fps") * 10_000_000L
                + f.optLong("bitrate");
    }

    private static String codec(JSONObject f) {
        Matcher m = CODECS.matcher(f.optString("mimeType"));
        return m.find() ? m.group(1) : "";
    }

    private static JSONObject player(Context context, String videoId, boolean refresh)
            throws IOException, JSONException, YoutubeException {
        String[] config = config(context, refresh);
        JSONObject client = new JSONObject()
                .put("clientName", "VISIONOS").put("clientVersion", CLIENT_VERSION)
                .put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
                .put("osName", "visionOS").put("osVersion", "26.5.23O471")
                .put("hl", "en").put("gl", "GB");
        JSONObject body = new JSONObject()
                .put("context", new JSONObject().put("client", client))
                .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true);
        HttpURLConnection conn = (HttpURLConnection) new URL(PLAYER + config[0]).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", AGENT);
            conn.setRequestProperty("Origin", "https://www.youtube.com");
            conn.setRequestProperty("X-YouTube-Client-Name", "101");
            conn.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
            conn.setRequestProperty("X-Goog-Visitor-Id", config[1]);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            return new JSONObject(read(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    /** {API key, visitor id} from a watch page, kept for a few hours. */
    private static String[] config(Context context, boolean refresh)
            throws IOException, YoutubeException {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("youtube", Context.MODE_PRIVATE);
        if (!refresh && System.currentTimeMillis() - prefs.getLong("at", 0) < CONFIG_FOR_MS) {
            return new String[]{prefs.getString("key", ""), prefs.getString("visitor", "")};
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(WATCH).openConnection();
        String page;
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", WEB_AGENT);
            conn.setRequestProperty("Accept-Language", "en-GB");
            page = read(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
        Matcher key = Pattern.compile("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").matcher(page);
        Matcher visitor = Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"").matcher(page);
        if (!key.find() || !visitor.find()) throw new YoutubeException("No API key in YouTube's page");
        prefs.edit().putLong("at", System.currentTimeMillis()).putString("key", key.group(1))
                .putString("visitor", visitor.group(1)).apply();
        return new String[]{key.group(1), visitor.group(1)};
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
