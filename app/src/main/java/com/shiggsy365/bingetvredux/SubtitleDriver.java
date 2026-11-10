package com.shiggsy365.bingetvredux;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
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

import com.shiggsy365.bingetvredux.stremio.Http;
import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.Subtitles;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Draws the player's subtitles on the app's own subtitle view, in {@link SubtitleStyle}, at an
 * offset (later +, earlier −). Subtitles from addons are downloaded and timed here, so they
 * can be moved either way; ones inside the video arrive as they're due, so they can only be
 * delayed. While an addon's are drawn, one of the video's own plays unseen, and addon subtitles
 * are lined up with it ({@link SubtitleSync}) until the offset is set by hand.
 */
@OptIn(markerClass = UnstableApi.class)
final class SubtitleDriver implements Player.Listener {
    private static final String TAG = "Subtitles";
    private static final long TICK_MS = 100;
    // Measured once playback has settled, as it downloads every subtitle.
    private static final long FITS_AFTER_MS = 20_000;
    private static final int MAX_FITS = 12;
    // How often lines from the file's index are looked at again around the playhead.
    private static final long FOLLOW_MS = 60_000;
    private static final int HOW_NONE = 0;
    private static final int HOW_HAND = 1;
    private static final int HOW_SYNCED = 2;
    private static final int HOW_UNMATCHED = 3;

    private final SubtitleView mView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Background.Tasks mExecutor = new Background.Tasks(1);
    private final Background.Tasks mFitTasks = new Background.Tasks(1);
    // The addons' subtitles, and how well each fits this stream (0 or missing: not known).
    private List<Subtitles.Track> mAddonTracks = new ArrayList<>();
    private Map<Subtitles.Track, Double> mFits = new java.util.HashMap<>();
    private final Runnable mMeasureFits = this::measureFits;
    private ExoPlayer mPlayer;
    private long mOffsetMs;
    // The addon subtitles being drawn (null: the video's own, or none), and their cues.
    private Subtitles.Track mExternal;
    private List<CuesWithTiming> mCues = new ArrayList<>();
    private int mGeneration;
    // Stretches addon subtitles made for another frame rate.
    private double mRatio = 1;
    // The stream playing: its index, and where its timings are remembered (null: not on demand).
    private Stream mStream;
    private Map<Long, List<SubtitleAlign.Span>> mIndex;
    // The addon subtitles' lines, for the sync; empty until they're in.
    private List<SubtitleAlign.Span> mLines = new ArrayList<>();
    // The sync (null: it hasn't started, or it's over).
    private SubtitleSync mSync;
    private int mSyncGeneration;
    // Waiting for the video's tracks, to pick one to match against.
    private boolean mReferencePending;
    // One of the video's own subtitles is playing unseen, for its lines.
    private boolean mListening;
    // When the line of the video's own now showing began.
    private long mLineStartMs = C.TIME_UNSET;
    // How the offset was set.
    private int mHow = HOW_NONE;
    private final Runnable mFollow = new Runnable() {
        @Override
        public void run() {
            SubtitleSync sync = mSync;
            if (sync == null || mPlayer == null) return;
            long position = mPlayer.getCurrentPosition();
            mExecutor.execute(() -> deliver(sync, sync.follow(position)));
            mHandler.postDelayed(this, FOLLOW_MS);
        }
    };
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
        // Another stream, another timing: the sync starts over when its subtitles are chosen.
        endSync();
        mFits = new java.util.HashMap<>();
        mHandler.removeCallbacks(mMeasureFits);
        if (mStream != null) mHandler.postDelayed(mMeasureFits, FITS_AFTER_MS);
    }

    /** The stream about to play (not for live TV), before {@link #attach}. */
    void setStream(Stream stream) {
        if (mStream == null || !mStream.url.equals(stream.url)) mIndex = null;
        mStream = stream;
    }

    void release() {
        if (mPlayer != null) mPlayer.removeListener(this);
        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
        mFitTasks.shutdownNow();
    }

    Subtitles.Track external() {
        return mExternal;
    }

    long offsetMs() {
        return mOffsetMs;
    }

    void setAddonTracks(List<Subtitles.Track> tracks) {
        mAddonTracks = tracks;
    }

    /** How well an addon's subtitles fit this stream, higher better; 0 when it isn't known. */
    double fit(Subtitles.Track track) {
        Double fit = mFits.get(track);
        return fit == null ? 0 : fit;
    }

    /** How the offset came about, for the menu: "" when there's nothing to say. */
    String timingNote() {
        switch (mHow) {
            case HOW_HAND: return "set by hand";
            case HOW_SYNCED: return mRatio != 1 ? "retimed and synced" : "synced";
            case HOW_UNMATCHED: return "no match";
            default: return "";
        }
    }

    /** Whether an addon's subtitles were moved by hand, so the sync can be asked back. */
    boolean canSyncAutomatically() {
        return mExternal != null && mHow == HOW_HAND;
    }

    /** Hands subtitles moved by hand back to the sync. */
    void syncAutomatically() {
        if (!canSyncAutomatically()) return;
        mHow = HOW_NONE;
        String key = timingKey();
        if (key != null) SubtitleTimings.remove(mView.getContext(), key);
        beginMatching();
    }

    /** The offset chosen by hand, which the sync then leaves alone. */
    void setOffsetMs(long offsetMs) {
        mOffsetMs = offsetMs;
        if (mExternal == null) {
            mView.setCues(new ArrayList<>());
        } else {
            mHow = HOW_HAND;
            if (mListening) stopListening();
            endSync();
            saveTiming();
        }
    }

    /** One subtitle on one version: null when it isn't on demand. */
    private String timingKey() {
        if (mStream == null || mExternal == null) return null;
        String version = mStream.filename == null || mStream.filename.isEmpty() ? mStream.dedupeKey() : mStream.filename;
        return version + "|" + mExternal.addon + "|" + (mExternal.id.isEmpty() ? mExternal.url : mExternal.id);
    }

    private void saveTiming() {
        String key = timingKey();
        if (key != null) SubtitleTimings.put(mView.getContext(), key, mOffsetMs, mRatio, mHow == HOW_HAND);
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

    /** An addon's subtitles: downloaded, parsed and drawn here; the video's own aren't shown. */
    void useExternal(Subtitles.Track track) {
        stopExternal();
        if (mPlayer != null) {
            mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
        mExternal = track;
        String key = timingKey();
        SubtitleTimings.Saved saved = key == null ? null : SubtitleTimings.get(mView.getContext(), key);
        if (saved != null) {
            mOffsetMs = saved.offsetMs;
            mRatio = saved.ratio;
            mHow = saved.byHand ? HOW_HAND : HOW_SYNCED;
        }
        int generation = ++mGeneration;
        mExecutor.execute(() -> {
            List<CuesWithTiming> cues = new ArrayList<>();
            try {
                cues = parse(track);
            } catch (Exception e) {
                Log.w(TAG, "Couldn't load " + track.addon + " subtitles: " + e.getMessage());
            }
            List<SubtitleAlign.Span> lines = spans(cues);
            List<CuesWithTiming> parsed = cues;
            mHandler.post(() -> {
                if (generation != mGeneration) return;
                mCues = parsed;
                mHandler.removeCallbacks(mTick);
                mHandler.post(mTick);
                mLines = lines;
                if (mHow != HOW_HAND) beginMatching();
            });
        });
    }

    private void stopExternal() {
        mGeneration++;
        mExternal = null;
        mCues = new ArrayList<>();
        mHandler.removeCallbacks(mTick);
        mView.setCues(new ArrayList<>());
        // What was right for the last subtitles says nothing about the next.
        mOffsetMs = 0;
        mRatio = 1;
        mHow = HOW_NONE;
        mLines = new ArrayList<>();
        endSync();
    }

    // ---- The video's own subtitles

    @Override
    public void onCues(CueGroup cueGroup) {
        if (mExternal != null) {
            hear(cueGroup);
            return;
        }
        List<Cue> cues = cueGroup.cues;
        if (mOffsetMs <= 0) mView.setCues(cues);
        else mHandler.postDelayed(() -> {
            if (mExternal == null) mView.setCues(cues);
        }, mOffsetMs);
    }

    // ---- Addon subtitles

    private void drawExternal() {
        if (mPlayer == null || mExternal == null) return;
        long at = Math.round((mPlayer.getCurrentPosition() - mOffsetMs) / mRatio * 1000);
        List<Cue> shown = new ArrayList<>();
        for (CuesWithTiming c : mCues) {
            if (c.startTimeUs > at) break;
            long end = endUs(c);
            if (end == C.TIME_UNSET || at < end) shown.addAll(c.cues);
        }
        mView.setCues(shown);
    }

    private static long endUs(CuesWithTiming c) {
        return c.durationUs == C.TIME_UNSET ? c.endTimeUs : c.startTimeUs + c.durationUs;
    }

    // ---- Lining addon subtitles up with the video's own

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (mReferencePending) beginMatching();
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        // A line cut short by a seek would be timed wrongly.
        mLineStartMs = C.TIME_UNSET;
    }

    /** Scores the addons' subtitles in the profile's languages against the file's index, if it has one. */
    private void measureFits() {
        if (mStream == null || mStream.url == null || mPlayer == null || mAddonTracks.isEmpty()) return;
        Stream stream = mStream;
        Map<Long, List<SubtitleAlign.Span>> known = mIndex;
        mFitTasks.execute(() -> {
            Map<Long, List<SubtitleAlign.Span>> index = known != null ? known : SubtitleIndex.read(stream.url, stream.headers);
            mHandler.post(() -> {
                if (stream != mStream) return;
                mIndex = index;
                List<SubtitleAlign.Span> video = indexedReference(null);
                if (video != null) scoreTracks(stream, video);
            });
        });
    }

    private void scoreTracks(Stream stream, List<SubtitleAlign.Span> video) {
        List<String> languages = Settings.subtitleLanguages(mView.getContext());
        List<Subtitles.Track> tracks = new ArrayList<>();
        for (Subtitles.Track t : mAddonTracks) {
            for (String language : languages) {
                if (VideoPlayerActivity.sameLanguage(t.lang, language) && tracks.size() < MAX_FITS) {
                    tracks.add(t);
                    break;
                }
            }
        }
        mFitTasks.execute(() -> {
            SubtitleAlign.Curve[] curves = SubtitleAlign.curves(SubtitleSync.LIMIT_MS);
            Map<Subtitles.Track, Double> fits = new java.util.HashMap<>();
            for (Subtitles.Track t : tracks) {
                try {
                    fits.put(t, SubtitleSync.fit(spans(parse(t)), video, curves));
                } catch (Exception e) {
                    Log.i(TAG, "Couldn't measure " + t.addon + " subtitles: " + e.getMessage());
                }
            }
            mHandler.post(() -> {
                if (stream == mStream) mFits = fits;
            });
        });
    }

    /** Reads the file's index, once per stream, then starts matching. */
    private void beginMatching() {
        endSync();
        if (mPlayer == null || mExternal == null || mLines.isEmpty()) return;
        mReferencePending = mPlayer.getCurrentTracks().isEmpty();
        if (mReferencePending) return;
        int generation = mSyncGeneration;
        if (mIndex != null || mStream == null || mStream.url == null) {
            startMatching();
            return;
        }
        String url = mStream.url;
        Map<String, String> headers = mStream.headers;
        mExecutor.execute(() -> {
            Map<Long, List<SubtitleAlign.Span>> index = SubtitleIndex.read(url, headers);
            mHandler.post(() -> {
                if (generation != mSyncGeneration) return;
                mIndex = index;
                startMatching();
            });
        });
    }

    /** Matches against the file's index when it has a track that will do, or else as the video plays. */
    private void startMatching() {
        if (mPlayer == null || mExternal == null) return;
        TrackSelectionOverride live = null;
        int liveRank = Integer.MAX_VALUE;
        for (Tracks.Group g : mPlayer.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                int rank = SubtitleSync.rank(f.sampleMimeType, f.codecs, f.selectionFlags, f.roleFlags, f.label,
                        VideoPlayerActivity.sameLanguage(mExternal.lang, f.language), false);
                if (rank >= 0 && rank < liveRank) {
                    liveRank = rank;
                    live = new TrackSelectionOverride(g.getMediaTrackGroup(), i);
                }
            }
        }
        List<SubtitleAlign.Span> indexed = indexedReference(mExternal.lang);
        if (indexed != null) {
            Log.i(TAG, "Matching against the file's index: " + indexed.size() + " lines");
            SubtitleSync sync = new SubtitleSync(mLines, mRatio);
            List<SubtitleAlign.Span> whole = indexed;
            long position = mPlayer.getCurrentPosition();
            mSync = sync;
            mExecutor.execute(() -> deliver(sync, sync.addWhole(whole, position)));
            mHandler.postDelayed(mFollow, FOLLOW_MS);
        } else if (live != null) {
            Log.i(TAG, "Matching as the video plays");
            mSync = new SubtitleSync(mLines, mRatio);
            mListening = true;
            mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(live).build());
        } else {
            Log.i(TAG, "Nothing in the video to match against");
        }
    }

    /** The best of the video's own subtitles in the file's index, for {@code language}; null when there's none. */
    private List<SubtitleAlign.Span> indexedReference(String language) {
        List<SubtitleAlign.Span> best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Tracks.Group g : mPlayer.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                int rank = SubtitleSync.rank(f.sampleMimeType, f.codecs, f.selectionFlags, f.roleFlags, f.label,
                        VideoPlayerActivity.sameLanguage(language, f.language), true);
                List<SubtitleAlign.Span> lines = indexLines(f.id);
                if (rank >= 0 && rank < bestRank && lines != null && lines.size() >= SubtitleSync.MIN_INDEX_LINES) {
                    bestRank = rank;
                    best = lines;
                }
            }
        }
        return best;
    }

    /** A track's lines in the index; the file's track number is the format's id. */
    private List<SubtitleAlign.Span> indexLines(String formatId) {
        if (mIndex == null || formatId == null) return null;
        try {
            return mIndex.get(Long.parseLong(formatId.substring(formatId.indexOf(':') + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<SubtitleAlign.Span> spans(List<CuesWithTiming> cues) {
        List<SubtitleAlign.Span> lines = new ArrayList<>(cues.size());
        for (CuesWithTiming c : cues) {
            long end = endUs(c);
            if (end != C.TIME_UNSET && !c.cues.isEmpty()) {
                lines.add(new SubtitleAlign.Span(c.startTimeUs / 1000d, end / 1000d));
            }
        }
        return lines;
    }

    /** A line of the video's own ends where the next change of its subtitles comes. */
    private void hear(CueGroup group) {
        if (!mListening || group.presentationTimeUs == C.TIME_UNSET) return;
        long at = group.presentationTimeUs / 1000;
        if (mLineStartMs != C.TIME_UNSET && at > mLineStartMs) {
            if (mSync != null) feed(java.util.Collections.singletonList(new SubtitleAlign.Span(mLineStartMs, at)));
        }
        mLineStartMs = group.cues.isEmpty() ? C.TIME_UNSET : at;
    }

    private void feed(List<SubtitleAlign.Span> lines) {
        SubtitleSync sync = mSync;
        long position = mPlayer.getCurrentPosition();
        mExecutor.execute(() -> deliver(sync, sync.add(lines, position)));
    }

    private void deliver(SubtitleSync sync, SubtitleSync.Result result) {
        if (result != null) mHandler.post(() -> {
            if (sync == mSync) applySync(result);
        });
    }

    private void applySync(SubtitleSync.Result result) {
        switch (result.kind) {
            case SubtitleSync.Result.DELAY:
                Log.i(TAG, "Synced: " + result.delayMs + " ms");
                mOffsetMs = result.delayMs;
                mHow = HOW_SYNCED;
                saveTiming();
                break;
            case SubtitleSync.Result.RATIO:
                Log.i(TAG, "Retimed: " + result.ratio);
                mRatio = result.ratio;
                mHow = HOW_SYNCED;
                saveTiming();
                SubtitleSync sync = mSync;
                long position = mPlayer.getCurrentPosition();
                mExecutor.execute(() -> deliver(sync, sync.follow(position)));
                break;
            default:
                Log.i(TAG, "These subtitles don't match the video's own");
                mHow = HOW_UNMATCHED;
                if (mListening) stopListening();
                endSync();
                break;
        }
    }

    /** The video's own subtitles aren't needed any more: they stop being read. */
    private void stopListening() {
        if (mPlayer != null) {
            mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
    }

    private void endSync() {
        mSync = null;
        mSyncGeneration++;
        mReferencePending = false;
        mListening = false;
        mLineStartMs = C.TIME_UNSET;
        mHandler.removeCallbacks(mFollow);
    }

    private static List<CuesWithTiming> parse(Subtitles.Track track) throws Exception {
        List<CuesWithTiming> cues = new ArrayList<>();
        byte[] data = download(track.url);
        parser(track.url, data).parse(data, 0, data.length, SubtitleParser.OutputOptions.allCues(), cues::add);
        java.util.Collections.sort(cues, (a, b) -> Long.compare(a.startTimeUs, b.startTimeUs));
        return cues;
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
        Http.Response r = Http.get(url).timeouts(15_000, 20_000).send();
        if (!r.ok()) throw new java.io.IOException("HTTP " + r.status);
        // A .gz file (not just a gzipped transfer) is unpacked here.
        if (!url.endsWith(".gz")) return r.bytes;
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(r.bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
