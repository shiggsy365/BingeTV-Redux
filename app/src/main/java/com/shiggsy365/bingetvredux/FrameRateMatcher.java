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
import androidx.media3.common.Format;
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
    private boolean mDecided;

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
            List<Long> gaps = new ArrayList<>();
            for (int i = SKIP + 1; i < mTimes.size(); i++) {
                long gap = mTimes.get(i) - mTimes.get(i - 1);
                if (gap > 0) gaps.add(gap);
            }
            if (gaps.isEmpty()) return;
            Collections.sort(gaps);
            float fps = 1_000_000f / gaps.get(gaps.size() / 2);
            if (format.frameRate > 0 && Math.abs(format.frameRate - fps) > 1) fps = format.frameRate;
            float measured = fps;
            mMain.post(() -> match(measured));
        }
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
        if (best != null && best.getModeId() != current.getModeId()) setMode(mActivity, best.getModeId());
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
