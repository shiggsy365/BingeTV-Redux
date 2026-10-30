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
    private static final long UP_NEXT_BEFORE_END_MS = 35_000;
    // Short videos (extras, specials) don't get Up Next.
    private static final long UP_NEXT_MIN_DURATION_MS = 10 * 60_000;

    private PlayerView mPlayerView;
    private Osd mOsd;
    private SubtitleDriver mSubtitles;
    // Whether this stream's subtitles have been chosen yet (once its tracks are known).
    private boolean mSubtitlesChosen;
    private View mUpNext;
    private TextView mUpNextCountdown;
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
        mPlayback = Playback.current();
        if (mPlayback == null || mPlayback.streams.isEmpty()) {
            finish();
            return;
        }
        mPlayerView = findViewById(R.id.player_view);
        mUpNext = findViewById(R.id.up_next);
        mUpNextCountdown = findViewById(R.id.up_next_countdown);
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
        mOsd.button(Osd.NEXT, "Next episode", () -> playEpisode(following), () -> following != null);
        mOsd.button(Osd.STREAMS, "Streams", this::showStreams, null);
        mOsd.addSettingsButtons();
        if (mPlayerView.getSubtitleView() != null) mPlayerView.getSubtitleView().setVisibility(View.GONE);
        mSubtitles = new SubtitleDriver(findViewById(R.id.subtitles));
        mOsd.setSubtitles(mSubtitles, mPlayback.subtitles);
        mOsd.setInfo(mPlayback.title(), mPlayback.meta.logo, osdLine(),
                String.join("  ·  ", mPlayback.meta.genres.subList(0, Math.min(3, mPlayback.meta.genres.size()))),
                mPlayback.video != null && !mPlayback.video.overview.isEmpty()
                        ? mPlayback.video.overview : mPlayback.meta.description);
        mIndex = mPlayback.start;
        mResumePosition = mPlayback.startMs;
        if (Settings.bool(this, Settings.AUTOPLAY_NEXT)) mNext = mPlayback.nextEpisode();
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
        mFrameRate = FrameRateMatcher.attach(this, mPlayer);
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
        mPlayer.setPlayWhenReady(true);
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
        if (mNext == null || mUpNextCancelled || duration == C.TIME_UNSET
                || duration < UP_NEXT_MIN_DURATION_MS) {
            return;
        }
        long left = duration - mPlayer.getCurrentPosition();
        if (left <= UP_NEXT_BEFORE_END_MS) {
            if (mUpNext.getVisibility() != View.VISIBLE) showUpNext();
            mUpNextCountdown.setText("Starting in " + Math.max(0, left / 1000)
                    + " s   ·   OK to play now   ·   Back to keep watching");
        }
    }

    private void showUpNext() {
        mUpNext.setVisibility(View.VISIBLE);
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
