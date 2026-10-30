package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.leanback.widget.VerticalGridView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.StreamInfo;
import com.shiggsy365.bingetvredux.stremio.Streams;
import com.shiggsy365.bingetvredux.stremio.Subtitles;
import com.shiggsy365.bingetvredux.stremio.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Locale;

/**
 * Plays a film or episode from its streams (see Playback).
 *
 * - If a stream fails or doesn't start, the next is tried (from the same place).
 * - Progress is saved as it plays; it starts where it was left when asked to.
 * - Menu lists the streams to switch to; the controls' buttons pick subtitles and audio.
 *   Subtitles follow the profile's languages, and are on or off as it says.
 * - Near the end of an episode, Up Next counts down to the next one (OK plays it now, Back
 *   cancels). Back otherwise stops playback.
 * - MDBList is told what's playing, if the profile scrobbles.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoPlayerActivity extends Activity {
    private static final String TAG = "VideoPlayer";
    // A debrid link can take a while to start, but not this long.
    private static final long START_TIMEOUT_MS = 45_000;
    private static final long SAVE_EVERY_MS = 15_000;
    // Short videos (extras, specials) don't get Up Next.
    private static final long UP_NEXT_MIN_DURATION_MS = 10 * 60_000;
    // The skip button's fallback, for episodes the databases don't know: a typical intro.
    private static final long SKIP_BY_MS = 85_000;
    // The "Skip intro" chip goes after this long; the OSD's skip button still works.
    private static final long SKIP_CHIP_MS = 12_000;
    // Material "redo" icon (Apache 2.0).
    private static final String SKIP_ICON = "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z";

    private PlayerView mPlayerView;
    private Osd mOsd;
    private SubtitleDriver mSubtitles;
    // Whether this stream's subtitles have been chosen yet (once its tracks are known).
    private boolean mSubtitlesChosen;
    // Nothing suited when the tracks came, and the addons' subtitles hadn't yet.
    private boolean mWaitingForSubtitles;
    private View mUpNext;
    private TextView mUpNextCountdown;
    // The intro, recap and credits, once known (see SkipTimes), and the chip that skips them.
    private List<SkipTimes.Segment> mSegments = Collections.emptyList();
    private View mSkip;
    private TextView mSkipLabel;
    private SkipTimes.Segment mSkipShown;
    private long mSkipShownAt;
    private final java.util.Set<SkipTimes.Segment> mSkipDone = new java.util.HashSet<>();
    private final Background.Tasks mTasks = new Background.Tasks(1);
    private ExoPlayer mPlayer;
    // Puts the TV in the display mode that suits the video (see FrameRateMatcher).
    private FrameRateMatcher mFrameRate;
    private Playback mPlayback;
    private int mIndex;
    private boolean mStarted;
    private long mResumePosition;
    private Video mNext;
    private boolean mUpNextCancelled;
    private boolean mLeaving;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStartTimeout = () -> failOver("didn't start");
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            tick();
            mHandler.postDelayed(this, 1_000);
        }
    };
    private long mLastSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.video_player_activity);
        // Radio stops and a podcast pauses: one thing playing at a time.
        AudioPlayer.yieldToVideo();
        mPlayback = Playback.current();
        if (mPlayback == null || mPlayback.streams.isEmpty()) {
            finish();
            return;
        }
        mPlayerView = findViewById(R.id.player_view);
        mUpNext = findViewById(R.id.up_next);
        mUpNextCountdown = findViewById(R.id.up_next_countdown);
        mSkip = findViewById(R.id.skip);
        mSkipLabel = findViewById(R.id.skip_label);
        mOsd = new Osd(this, mPlayerView, new Osd.Host() {
            @Override
            ExoPlayer player() {
                return mPlayer;
            }

            @Override
            void stop() {
                finish();
            }
        });
        Video previous = mPlayback.previousEpisode(), following = mPlayback.nextEpisode();
        mOsd.button(Osd.PREVIOUS, "Previous episode", () -> playEpisode(previous), () -> previous != null);
        mOsd.addPlaybackButtons();
        boolean episode = mPlayback.video != null && mPlayback.video.season != Video.NONE;
        mOsd.button(SKIP_ICON, "Skip " + SKIP_BY_MS / 1000 + " s", this::skipAhead, () -> episode);
        mOsd.button(Osd.NEXT, "Next episode", () -> playEpisode(following), () -> following != null);
        mOsd.button(Osd.STREAMS, "Streams", this::showStreams, null);
        mOsd.addSettingsButtons();
        if (mPlayerView.getSubtitleView() != null) mPlayerView.getSubtitleView().setVisibility(View.GONE);
        mSubtitles = new SubtitleDriver(findViewById(R.id.subtitles));
        mOsd.setSubtitles(mSubtitles, mPlayback.subtitles);
        // The subtitle addons may answer after the video starts.
        if (!mPlayback.subtitlesKnown()) {
            mPlayback.setOnSubtitles(() -> {
                if (isDestroyed()) return;
                mOsd.setSubtitles(mSubtitles, mPlayback.subtitles);
                if (mWaitingForSubtitles && mPlayer != null) chooseSubtitles(mPlayer.getCurrentTracks());
            });
        }
        mOsd.setInfo(mPlayback.title(), mPlayback.meta.logo, osdLine(),
                String.join("  ·  ", mPlayback.meta.genres.subList(0, Math.min(3, mPlayback.meta.genres.size()))),
                mPlayback.video != null && !mPlayback.video.overview.isEmpty()
                        ? mPlayback.video.overview : mPlayback.meta.description);
        mIndex = mPlayback.start;
        mResumePosition = mPlayback.startMs;
        if (Settings.bool(this, Settings.AUTOPLAY_NEXT)) mNext = mPlayback.nextEpisode();
        if (episode) {
            mTasks.execute(() -> {
                List<SkipTimes.Segment> found = SkipTimes.find(mPlayback.meta, mPlayback.video);
                runOnUiThread(() -> {
                    if (!isDestroyed()) mSegments = found;
                });
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mPlayback != null && mPlayer == null && !mLeaving) play(mIndex, mResumePosition);
        mHandler.post(mTick);
    }

    @Override
    protected void onStop() {
        mHandler.removeCallbacks(mTick);
        if (mPlayer != null && mStarted) {
            mResumePosition = mPlayer.getCurrentPosition();
            save(Mdblist.STOP);
        }
        release();
        super.onStop();
    }

    /** "S1 E2  Pilot", or a film's year, length and rating. */
    private String osdLine() {
        if (mPlayback.video != null && mPlayback.video.season != Video.NONE) {
            return DetailsActivity.episodeCode(mPlayback.video)
                    + (mPlayback.video.title.isEmpty() ? "" : "  ·  " + mPlayback.video.title);
        }
        return String.join("  ·  ", InfoChips.facts(mPlayback.meta));
    }

    /** Another episode, from the start (from the source this one came from, if it has it). */
    private void playEpisode(Video video) {
        if (video == null || mLeaving) return;
        mLeaving = true;
        save(Mdblist.STOP);
        release();
        Playback.start(this, new Playback(mPlayback.meta, video, 0, current().bingeGroup), false);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mPlayback != null) mPlayback.setOnSubtitles(null);
        mTasks.shutdownNow();
        if (mOsd != null) mOsd.release();
        if (mSubtitles != null) mSubtitles.release();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (mUpNext.getVisibility() == View.VISIBLE) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (up) playNext();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                if (up) {
                    mUpNextCancelled = true;
                    mUpNext.setVisibility(View.GONE);
                }
                return true;
            }
        }
        if (mSkip.getVisibility() == View.VISIBLE && mSkipShown != null && !mOsd.isShowing()) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (up) skip(mSkipShown);
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                if (up) hideSkip(true);
                return true;
            }
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (up) showStreams();
            return true;
        }
        if (mOsd != null && mOsd.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // ---- Playing ----

    private void play(int index, long position) {
        release();
        mIndex = index;
        mStarted = false;
        Stream stream = mPlayback.streams.get(index);
        mOsd.setStream(StreamInfo.of(stream));
        Log.i(TAG, "Stream " + (index + 1) + " of " + mPlayback.streams.size() + ": "
                + String.join(" ", Streams.tags(stream)));
        mPlayer = Players.build(this, stream.headers);
        // Plays once the TV is in the video's mode, rather than switching a second into it.
        mFrameRate = FrameRateMatcher.attachBeforePlay(this, mPlayer,
                findViewById(R.id.frame_rate_cover));
        mPlayer.setTrackSelectionParameters(trackPrefs(mPlayer.getTrackSelectionParameters()));
        mSubtitles.attach(mPlayer);
        mSubtitlesChosen = false;
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !mStarted) {
                    mStarted = true;
                    mHandler.removeCallbacks(mStartTimeout);
                    WatchStore.get(VideoPlayerActivity.this).touch(mPlayback.entry(current()));
                    Mdblist.scrobble(VideoPlayerActivity.this, Mdblist.START,
                            mPlayback.entry(current()), percent());
                } else if (state == Player.STATE_ENDED) {
                    finished();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (!mStarted || mPlayer == null) return;
                if (!isPlaying && mPlayer.getPlaybackState() == Player.STATE_READY) {
                    save(Mdblist.PAUSE);
                } else if (isPlaying) {
                    Mdblist.scrobble(VideoPlayerActivity.this, Mdblist.START,
                            mPlayback.entry(current()), percent());
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (!mSubtitlesChosen && !tracks.getGroups().isEmpty()) {
                    mSubtitlesChosen = true;
                    chooseSubtitles(tracks);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Stream error: " + error.getErrorCodeName(), error);
                if (mStarted && mPlayer != null) mResumePosition = mPlayer.getCurrentPosition();
                failOver(error.getErrorCodeName());
            }
        });
        // Addon subtitles aren't the player's: SubtitleDriver draws them (so they can be offset).
        MediaItem item = new MediaItem.Builder().setUri(Uri.parse(stream.url)).build();
        mPlayer.setMediaItem(item, position);
        mPlayerView.setPlayer(mPlayer);
        // FrameRateMatcher starts it.
        mPlayer.setPlayWhenReady(false);
        mPlayer.prepare();
        mHandler.removeCallbacks(mStartTimeout);
        mHandler.postDelayed(mStartTimeout, START_TIMEOUT_MS);
    }

    private Stream current() {
        return mPlayback.streams.get(mIndex);
    }

    /** The profile's audio and subtitle languages, with subtitles on or off as it says. */
    private TrackSelectionParameters trackPrefs(TrackSelectionParameters params) {
        boolean on = Settings.bool(this, Settings.SUBTITLES_ON);
        return params.buildUpon()
                .setPreferredTextLanguages(Settings.subtitleLanguages(this).toArray(new String[0]))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
                .setPreferredAudioLanguages(Settings.audioLanguages(this).toArray(new String[0]))
                .build();
    }

    /**
     * With subtitles on: the video's own track in a preferred language (the player picked it),
     * else the first addon track in one, in the profile's order.
     */
    private void chooseSubtitles(Tracks tracks) {
        mWaitingForSubtitles = false;
        if (!Settings.bool(this, Settings.SUBTITLES_ON)) return;
        List<String> languages = Settings.subtitleLanguages(this);
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSelected(i) && matches(g.getTrackFormat(i).language, languages)) return;
            }
        }
        for (String language : languages) {
            for (Subtitles.Track t : mPlayback.subtitles) {
                if (matches(t.lang, Collections.singletonList(language))) {
                    mSubtitles.useExternal(t);
                    return;
                }
            }
        }
        mWaitingForSubtitles = !mPlayback.subtitlesKnown();
    }

    /** Whether a track's language (any ISO 639 form) is one of {@code languages} (ISO 639-2). */
    private static boolean matches(String language, List<String> languages) {
        if (language == null || language.isEmpty()) return false;
        String code;
        try {
            code = new Locale(language.split("[-_]")[0]).getISO3Language();
        } catch (java.util.MissingResourceException e) {
            code = language.toLowerCase(Locale.ROOT);
        }
        return languages.contains(code);
    }

    private void failOver(String reason) {
        int next = mIndex + 1;
        if (next >= mPlayback.streams.size()) {
            Toast.makeText(this, "None of the streams would play", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Log.i(TAG, "Stream " + (mIndex + 1) + " " + reason + "; trying the next");
        Toast.makeText(this, "That stream " + (reason.equals("didn't start") ? "didn't start"
                : "failed") + ", trying the next", Toast.LENGTH_SHORT).show();
        play(next, mResumePosition);
    }

    private void release() {
        mHandler.removeCallbacks(mStartTimeout);
        if (mPlayer != null) {
            mPlayerView.setPlayer(null);
            if (mFrameRate != null) {
                mFrameRate.release();
                mFrameRate = null;
            }
            mPlayer.release();
            mPlayer = null;
        }
    }

    // ---- Progress ----

    private int percent() {
        if (mPlayer == null) return 0;
        long duration = mPlayer.getDuration();
        return duration > 0 ? (int) (mPlayer.getCurrentPosition() * 100 / duration) : 0;
    }

    /** Saves where it's got to, and tells MDBList ({@code action}). */
    private void save(String action) {
        if (mPlayer == null || !mStarted) return;
        long duration = mPlayer.getDuration();
        if (duration == C.TIME_UNSET) return;
        WatchStore.Entry entry = mPlayback.entry(current());
        WatchStore.get(this).record(entry, mPlayer.getCurrentPosition(), duration,
                Settings.integer(this, Settings.WATCHED_PERCENT));
        if (action != null) Mdblist.scrobble(this, action, entry, percent());
        mLastSave = System.currentTimeMillis();
    }

    private void tick() {
        if (mPlayer == null || !mStarted) return;
        if (mPlayer.isPlaying() && System.currentTimeMillis() - mLastSave > SAVE_EVERY_MS) {
            save(null);
        }
        long duration = mPlayer.getDuration();
        long position = mPlayer.getCurrentPosition();
        updateSkip(position);
        if (mNext == null || mUpNextCancelled || duration == C.TIME_UNSET
                || duration < UP_NEXT_MIN_DURATION_MS) {
            return;
        }
        long left = duration - position;
        // When depends on the episode's length (the profile's settings), or as the credits
        // start, when they're known.
        SkipTimes.Segment credits = segment(SkipTimes.CREDITS);
        if (left <= Settings.upNextBeforeEndMs(this, duration)
                || (credits != null && position >= credits.startMs && left > 0)) {
            if (mUpNext.getVisibility() != View.VISIBLE) showUpNext();
            mUpNextCountdown.setText("Up next  ·  in " + Math.max(0, left / 1000) + " s");
        }
    }

    // ---- Skipping the intro, recap and credits

    private SkipTimes.Segment segment(String kind) {
        for (SkipTimes.Segment s : mSegments) if (s.kind.equals(kind)) return s;
        return null;
    }

    /** The chip, while a part that can be skipped plays (credits only without Up Next). */
    private void updateSkip(long position) {
        SkipTimes.Segment now = null;
        for (SkipTimes.Segment s : mSegments) {
            if (!s.contains(position) || mSkipDone.contains(s)) continue;
            boolean upNextTakesIt = s.kind.equals(SkipTimes.CREDITS) && mNext != null && !mUpNextCancelled;
            if (!upNextTakesIt) now = s;
        }
        if (now != null && now == mSkipShown
                && System.currentTimeMillis() - mSkipShownAt > SKIP_CHIP_MS) {
            hideSkip(true);
            return;
        }
        if (now == null || mOsd.isShowing() || mUpNext.getVisibility() == View.VISIBLE) {
            if (mSkip.getVisibility() == View.VISIBLE) hideSkip(false);
            return;
        }
        if (now != mSkipShown) {
            mSkipShown = now;
            mSkipShownAt = System.currentTimeMillis();
            mSkipLabel.setText(now.label());
        }
        mSkip.setVisibility(View.VISIBLE);
    }

    /** Hides the chip; {@code done}: not again for this part. */
    private void hideSkip(boolean done) {
        if (done && mSkipShown != null) mSkipDone.add(mSkipShown);
        mSkip.setVisibility(View.GONE);
        if (done) mSkipShown = null;
    }

    private void skip(SkipTimes.Segment segment) {
        hideSkip(true);
        if (mPlayer == null) return;
        long duration = mPlayer.getDuration();
        long to = segment.endMs;
        if (duration != C.TIME_UNSET && to >= duration - 1_000) {
            finished();
            return;
        }
        mPlayer.seekTo(to);
    }

    /** The OSD's skip button: past the intro when it's known and still ahead, else 85 s on. */
    private void skipAhead() {
        if (mPlayer == null) return;
        long position = mPlayer.getCurrentPosition();
        for (SkipTimes.Segment s : mSegments) {
            if (!s.kind.equals(SkipTimes.CREDITS) && s.endMs > position && s.startMs - position < 60_000) {
                skip(s);
                return;
            }
        }
        long duration = mPlayer.getDuration();
        long to = position + SKIP_BY_MS;
        mPlayer.seekTo(duration == C.TIME_UNSET ? to : Math.min(to, Math.max(0, duration - 1_000)));
    }

    private void showUpNext() {
        mUpNext.setVisibility(View.VISIBLE);
        // Its streams and subtitles, found while the countdown runs.
        StreamPrefetch.start(this, mPlayback.meta.type, mNext.id);
        ((TextView) findViewById(R.id.up_next_title)).setText(
                DetailsActivity.episodeCode(mNext) + (mNext.title.isEmpty() ? "" : "  " + mNext.title));
        Images.load(findViewById(R.id.up_next_image), mNext.thumbnail.isEmpty()
                ? mPlayback.meta.background : mNext.thumbnail);
    }

    /** The end: this counts as watched, and the next episode starts if Up Next is on. */
    private void finished() {
        if (mNext != null && !mUpNextCancelled) {
            playNext();
            return;
        }
        markWatched();
        finish();
    }

    private void markWatched() {
        if (mPlayer == null || !mStarted) return;
        long duration = mPlayer.getDuration();
        WatchStore.Entry entry = mPlayback.entry(current());
        WatchStore.get(this).record(entry, duration, duration, 0);
        Mdblist.scrobble(this, Mdblist.STOP, entry, 100);
    }

    private void playNext() {
        if (mLeaving) return;
        mLeaving = true;
        markWatched();
        release();
        Playback.start(this, new Playback(mPlayback.meta, mNext, 0, current().bingeGroup), false);
        finish();
    }

    // ---- Streams menu ----

    private void showStreams() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.player_streams);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        VerticalGridView list = dialog.findViewById(R.id.list);
        List<StreamInfo> infos = new ArrayList<>();
        for (Stream s : mPlayback.streams) infos.add(StreamInfo.of(s));
        StreamCards.Adapter cards = new StreamCards.Adapter(mPlayback.streams, infos, mIndex, index -> {
            dialog.dismiss();
            if (index == mIndex) return;
            if (mPlayer != null && mStarted) mResumePosition = mPlayer.getCurrentPosition();
            play(index, mResumePosition);
        }, info -> {});
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < mPlayback.streams.size(); i++) all.add(i);
        cards.show(all);
        float dp = getResources().getDisplayMetrics().density;
        list.setNumColumns(2);
        list.setHorizontalSpacing(Math.round(8 * dp));
        list.setVerticalSpacing(Math.round(10 * dp));
        list.setAdapter(cards);
        list.setSelectedPosition(mIndex);
        mOsd.hide();
        dialog.show();
    }
}
