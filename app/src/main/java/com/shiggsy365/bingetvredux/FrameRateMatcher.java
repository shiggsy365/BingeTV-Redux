package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Puts the TV in the display mode that suits the video's frame rate: 25 and 50 fps (UK
 * television) at 50 Hz, 29.97/59.94 at 59.94 Hz, 30/60 at 60 Hz. At 60 Hz, 25 fps video
 * judders (frames are held for 2 and 3 refreshes in turn). The rate is measured from the
 * frames' own timestamps, so streams that don't declare one (most live TV) are matched too.
 * The mode the activity had is put back by {@link #release()}.
 *
 * {@link #attachBeforePlay} changes mode before the video is seen: at once for a video that
 * declares its rate; otherwise it plays muted under a cover while it's measured, then goes
 * back to the start once the display has changed.
 */
@OptIn(markerClass = UnstableApi.class)
final class FrameRateMatcher implements VideoFrameMetadataListener {
    private static final String TAG = "FrameRate";
    // Frames timed before deciding (the first few after starting are skipped).
    private static final int SKIP = 8, SAMPLES = 40;

    /** Remembers the last live TV rate, so the guide can switch as it opens (see prepare). */
    static final String LIVE = "live";
    private static final String PREFS = "frame_rate";

    private final Activity mActivity;
    private final ExoPlayer mPlayer;
    private String mKind;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Long> mTimes = new ArrayList<>();
    private volatile boolean mDecided;
    // attachBeforePlay: waiting for the display to reach this mode before playing (0: not).
    private int mWaitingFor;
    private android.hardware.display.DisplayManager.DisplayListener mDisplayListener;
    private Player.Listener mTracksListener;
    // The longest a mode change is waited for before playing anyway.
    private static final long SWITCH_TIMEOUT_MS = 4_000;
    private final Runnable mPlayAnyway = this::startPlaying;
    // Measuring under the cover (attachBeforePlay, no declared rate): where to go back to.
    private android.view.View mCover;
    private boolean mCovered;
    private long mStartAt;
    private Player.Listener mRevealListener;
    private final Runnable mRevealAnyway = this::uncover;
    // Standard rates a measurement snaps to (timestamps are often rounded to the millisecond).
    private static final float[] RATES = {23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f};

    private FrameRateMatcher(Activity activity, ExoPlayer player) {
        mActivity = activity;
        mPlayer = player;
    }

    /** Starts matching for this player's video. */
    static FrameRateMatcher attach(Activity activity, ExoPlayer player) {
        FrameRateMatcher matcher = new FrameRateMatcher(activity, player);
        player.setVideoFrameMetadataListener(matcher);
        return matcher;
    }

    /**
     * Matching before playback: the caller prepares the player without playing, and this
     * starts it once the display is in the mode the video's declared rate needs. Without a
     * declared rate it starts straight away and the rate is measured as it plays.
     */
    static FrameRateMatcher attachBeforePlay(Activity activity, ExoPlayer player, android.view.View cover) {
        FrameRateMatcher matcher = attach(activity, player);
        matcher.mCover = cover;
        matcher.mTracksListener = new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                matcher.tracksKnown(tracks);
            }
        };
        player.addListener(matcher.mTracksListener);
        return matcher;
    }

    private void tracksKnown(Tracks tracks) {
        if (mTracksListener == null || mDecided || tracks.getGroups().isEmpty()) return;
        float fps = 0;
        boolean video = false;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            video = true;
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSelected(i)) fps = g.getTrackFormat(i).frameRate;
            }
        }
        mPlayer.removeListener(mTracksListener);
        mTracksListener = null;
        if (!video) {
            startPlaying();
            return;
        }
        if (fps <= 0) {
            // Measured from the frames as it plays, out of sight and muted, then from the start.
            mCovered = true;
            mStartAt = mPlayer.getCurrentPosition();
            mCover.setVisibility(android.view.View.VISIBLE);
            mPlayer.setVolume(0f);
            mMain.postDelayed(mRevealAnyway, 8_000);
            mPlayer.setPlayWhenReady(true);
            return;
        }
        mDecided = true;
        Display.Mode current = mActivity.getWindowManager().getDefaultDisplay().getMode();
        Display.Mode best = bestMode(mActivity, fps);
        Log.i(TAG, String.format(java.util.Locale.ROOT, "Video declares %.3f fps; display %.3f Hz; best %s",
                fps, current.getRefreshRate(), best == null ? "none" : best.getRefreshRate() + " Hz"));
        if (best == null) {
            startPlaying();
            return;
        }
        // Asked for even when the display is in it already: a stream before this one may have
        // just asked for the usual mode back (release).
        setMode(mActivity, best.getModeId());
        if (best.getModeId() == current.getModeId()) {
            startPlaying();
            return;
        }
        waitForMode(best.getModeId(), this::startPlaying);
    }

    /** Runs {@code then} once the display is in mode {@code modeId} (or after a few seconds). */
    private void waitForMode(int modeId, Runnable then) {
        mWaitingFor = modeId;
        mWhenSwitched = then;
        android.hardware.display.DisplayManager displays =
                mActivity.getSystemService(android.hardware.display.DisplayManager.class);
        mDisplayListener = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}

            @Override
            public void onDisplayChanged(int displayId) {
                if (mActivity.getWindowManager().getDefaultDisplay().getMode().getModeId() == mWaitingFor) {
                    // A moment for the TV to show the new mode's picture.
                    mMain.removeCallbacks(mSwitched);
                    mMain.postDelayed(mSwitched, 300);
                }
            }
        };
        displays.registerDisplayListener(mDisplayListener, mMain);
        mMain.postDelayed(mSwitched, SWITCH_TIMEOUT_MS);
    }

    private Runnable mWhenSwitched;
    private final Runnable mSwitched = () -> {
        mMain.removeCallbacks(this.mSwitched);
        stopWaiting();
        Runnable then = mWhenSwitched;
        mWhenSwitched = null;
        if (then != null) then.run();
    };

    /** Measured under the cover: back to the start, with the sound, once it's drawn again. */
    private void uncover() {
        mMain.removeCallbacks(mRevealAnyway);
        if (!mCovered || mActivity.isDestroyed()) return;
        mCovered = false;
        mPlayer.setVolume(1f);
        mRevealListener = new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                hideCover();
            }
        };
        mPlayer.addListener(mRevealListener);
        mPlayer.seekTo(mStartAt);
        mMain.postDelayed(this::hideCover, 3_000);
    }

    private void hideCover() {
        if (mRevealListener != null) mPlayer.removeListener(mRevealListener);
        mRevealListener = null;
        if (mCover != null) mCover.setVisibility(android.view.View.GONE);
    }

    private void startPlaying() {
        mMain.removeCallbacks(mPlayAnyway);
        mMain.removeCallbacks(mSwitched);
        stopWaiting();
        if (!mActivity.isDestroyed()) mPlayer.setPlayWhenReady(true);
    }

    private void stopWaiting() {
        mWaitingFor = 0;
        if (mDisplayListener != null) {
            mActivity.getSystemService(android.hardware.display.DisplayManager.class)
                    .unregisterDisplayListener(mDisplayListener);
            mDisplayListener = null;
        }
    }

    /** The same, remembering the rate found for this kind of video (e.g. {@link #LIVE}). */
    static FrameRateMatcher attach(Activity activity, ExoPlayer player, String kind) {
        FrameRateMatcher matcher = attach(activity, player);
        matcher.mKind = kind;
        return matcher;
    }

    /**
     * Switches to the mode the last video of this kind needed (UK television's 50 Hz the first
     * time), as a screen opens: the screen blanks once while it's appearing, instead of again
     * when its video starts. Call before the content is drawn.
     */
    static void prepare(Activity activity, String kind) {
        float fps = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getFloat(kind, 25f);
        Display.Mode best = bestMode(activity, fps);
        if (best != null) setMode(activity, best.getModeId());
    }

    /**
     * Stops listening. The display goes back to its usual mode, except for live TV, whose
     * mode stays while its screen is open (changing channel shouldn't blank the screen twice;
     * leaving the screen puts the display back).
     */
    void release() {
        mPlayer.clearVideoFrameMetadataListener(this);
        if (mTracksListener != null) mPlayer.removeListener(mTracksListener);
        mTracksListener = null;
        mMain.removeCallbacks(mPlayAnyway);
        mMain.removeCallbacks(mSwitched);
        mMain.removeCallbacks(mRevealAnyway);
        stopWaiting();
        if (mRevealListener != null) mPlayer.removeListener(mRevealListener);
        if (mKind == null) mMain.post(() -> setMode(0));
    }

    @Override
    public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs,
            Format format, @Nullable MediaFormat mediaFormat) {
        if (mDecided) return;
        synchronized (mTimes) {
            mTimes.add(presentationTimeUs);
            if (mTimes.size() < SKIP + SAMPLES) return;
            mDecided = true;
            // Over the whole sample, not frame to frame: rounded timestamps (41, 42, 42 ms…)
            // average out. Then the standard rate it's close to.
            List<Long> times = new ArrayList<>(mTimes.subList(SKIP, mTimes.size()));
            Collections.sort(times);
            long span = times.get(times.size() - 1) - times.get(0);
            if (span <= 0) return;
            float fps = snap((times.size() - 1) * 1_000_000f / span);
            if (format.frameRate > 0 && Math.abs(format.frameRate - fps) > 1) fps = format.frameRate;
            float measured = fps;
            mMain.post(() -> match(measured));
        }
    }

    /** The nearest standard rate, if one is within 1% of {@code fps}; else {@code fps}. */
    static float snap(float fps) {
        float best = fps, off = Float.MAX_VALUE;
        for (float rate : RATES) {
            float d = Math.abs(fps - rate);
            if (d <= rate * 0.01f && d < off) {
                best = rate;
                off = d;
            }
        }
        return best;
    }

    private void match(float fps) {
        if (mActivity.isDestroyed()) return;
        if (mKind != null) {
            mActivity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putFloat(mKind, fps).apply();
        }
        Display.Mode current = mActivity.getWindowManager().getDefaultDisplay().getMode();
        Display.Mode best = bestMode(mActivity, fps);
        Log.i(TAG, String.format(java.util.Locale.ROOT, "Video %.3f fps; display %.3f Hz; best %s", fps,
                current.getRefreshRate(), best == null ? "none (left as it is)" : best.getRefreshRate() + " Hz"));
        if (best != null && best.getModeId() != current.getModeId()) {
            setMode(mActivity, best.getModeId());
            if (mCovered) waitForMode(best.getModeId(), this::uncover);
        } else {
            uncover();
        }
    }

    /** The lowest refresh rate at the current size that the frames divide into evenly; null if none. */
    private static Display.Mode bestMode(Activity activity, float fps) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = null;
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() != current.getPhysicalWidth()
                    || m.getPhysicalHeight() != current.getPhysicalHeight()) {
                continue;
            }
            // A refresh rate the frames divide into evenly (1×, 2×…), within 0.2%.
            float ratio = m.getRefreshRate() / fps;
            long whole = Math.round(ratio);
            if (whole < 1 || Math.abs(ratio - whole) > 0.002 * whole) continue;
            if (best == null || m.getRefreshRate() < best.getRefreshRate()) best = m;
        }
        return best;
    }

    private void setMode(int modeId) {
        setMode(mActivity, modeId);
    }

    private static void setMode(Activity activity, int modeId) {
        Window window = activity.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        if (lp.preferredDisplayModeId == modeId) return;
        lp.preferredDisplayModeId = modeId;
        window.setAttributes(lp);
    }
}
