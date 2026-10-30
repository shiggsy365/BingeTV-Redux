package com.shiggsy365.bingetvredux;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.ssa.SsaParser;
import androidx.media3.extractor.text.subrip.SubripParser;
import androidx.media3.extractor.text.webvtt.WebvttParser;
import androidx.media3.ui.SubtitleView;

import com.shiggsy365.bingetvredux.stremio.Subtitles;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Draws the player's subtitles on the app's own subtitle view, in {@link SubtitleStyle}, at an
 * offset (later +, earlier −). Subtitles from addons are downloaded and timed here, so they
 * can be moved either way; ones inside the video arrive as they're due, so they can only be
 * delayed.
 */
@OptIn(markerClass = UnstableApi.class)
final class SubtitleDriver implements Player.Listener {
    private static final String TAG = "Subtitles";
    private static final long TICK_MS = 100;

    private final SubtitleView mView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private ExoPlayer mPlayer;
    private long mOffsetMs;
    // The addon subtitles being drawn (null: the video's own, or none), and their cues.
    private Subtitles.Track mExternal;
    private List<CuesWithTiming> mCues = new ArrayList<>();
    private int mGeneration;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            drawExternal();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    SubtitleDriver(SubtitleView view) {
        mView = view;
        SubtitleStyle.apply(view);
    }

    void attach(ExoPlayer player) {
        if (mPlayer != null) mPlayer.removeListener(this);
        mPlayer = player;
        player.addListener(this);
    }

    void release() {
        if (mPlayer != null) mPlayer.removeListener(this);
        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
    }

    Subtitles.Track external() {
        return mExternal;
    }

    long offsetMs() {
        return mOffsetMs;
    }

    void setOffsetMs(long offsetMs) {
        mOffsetMs = offsetMs;
        if (mExternal == null) mView.setCues(new ArrayList<>());
    }

    void restyle() {
        SubtitleStyle.apply(mView);
    }

    /** No subtitles at all. */
    void off() {
        stopExternal();
        if (mPlayer != null) {
            mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
        mView.setCues(new ArrayList<>());
    }

    /** The video's own subtitles (the player picks the track; see Osd). */
    void useEmbedded() {
        stopExternal();
    }

    /** An addon's subtitles: downloaded, parsed and drawn here; the video's own are turned off. */
    void useExternal(Subtitles.Track track) {
        stopExternal();
        if (mPlayer != null) {
            mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
        mExternal = track;
        int generation = ++mGeneration;
        mExecutor.execute(() -> {
            List<CuesWithTiming> cues = new ArrayList<>();
            try {
                byte[] data = download(track.url);
                parser(track.url, data).parse(data, 0, data.length, SubtitleParser.OutputOptions.allCues(), cues::add);
                java.util.Collections.sort(cues, (a, b) -> Long.compare(a.startTimeUs, b.startTimeUs));
            } catch (Exception e) {
                Log.w(TAG, "Couldn't load " + track.addon + " subtitles: " + e.getMessage());
            }
            mHandler.post(() -> {
                if (generation != mGeneration) return;
                mCues = cues;
                mHandler.removeCallbacks(mTick);
                mHandler.post(mTick);
            });
        });
    }

    private void stopExternal() {
        mGeneration++;
        mExternal = null;
        mCues = new ArrayList<>();
        mHandler.removeCallbacks(mTick);
        mView.setCues(new ArrayList<>());
    }

    // ---- The video's own subtitles

    @Override
    public void onCues(CueGroup cueGroup) {
        if (mExternal != null) return;
        List<Cue> cues = cueGroup.cues;
        if (mOffsetMs <= 0) mView.setCues(cues);
        else mHandler.postDelayed(() -> {
            if (mExternal == null) mView.setCues(cues);
        }, mOffsetMs);
    }

    // ---- Addon subtitles

    private void drawExternal() {
        if (mPlayer == null || mExternal == null) return;
        long at = (mPlayer.getCurrentPosition() - mOffsetMs) * 1000;
        List<Cue> shown = new ArrayList<>();
        for (CuesWithTiming c : mCues) {
            if (c.startTimeUs > at) break;
            long end = c.durationUs == C.TIME_UNSET ? c.endTimeUs : c.startTimeUs + c.durationUs;
            if (end == C.TIME_UNSET || at < end) shown.addAll(c.cues);
        }
        mView.setCues(shown);
    }

    private static SubtitleParser parser(String url, byte[] data) {
        String path = Uri.parse(url).getPath();
        path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String head = new String(data, 0, Math.min(data.length, 64), java.nio.charset.StandardCharsets.UTF_8);
        if (path.endsWith(".vtt") || head.contains("WEBVTT")) return new WebvttParser();
        if (path.endsWith(".ass") || path.endsWith(".ssa") || head.contains("[Script Info]")) return new SsaParser();
        return new SubripParser();
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(true);
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding()) || url.endsWith(".gz")) in = new GZIPInputStream(in);
            try (InputStream input = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
