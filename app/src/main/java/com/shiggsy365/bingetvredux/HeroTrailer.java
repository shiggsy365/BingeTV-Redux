package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;


/**
 * Home's trailers: when focus rests on a title for a few seconds (the profile's setting), its
 * YouTube trailer plays in a window at the top right, with or without sound, fading in over the
 * title's artwork and blending into it at the edges (see EdgeFadeFrame). Moving on stops it. A title whose catalog entry doesn't name a trailer is looked up in full
 * first. Black bars in its picture (see Letterbox) are found in its first frames, before it's
 * seen, and left out.
 */
final class HeroTrailer {
    private static final String TAG = "HeroTrailer";
    private static final int MAX_HEIGHT = 720;
    // Frames looked at for black bars: this often, this small.
    private static final long SAMPLE_EVERY_MS = 250;
    private static final int SAMPLE_WIDTH = 160, SAMPLE_HEIGHT = 90;
    // Shown once this many frames agree on the bars, or this long after its first frame
    // (trailers open on dark logos), as it is then.
    private static final int SAMPLES_AGREED = 3;
    private static final long SHOW_BY_MS = 2_500;
    // Looked at for this long in all: a brighter frame may show the bars are narrower.
    private static final long SAMPLE_FOR_MS = 15_000;
    // A little more than the bars, for their soft edge.
    private static final float CROP_MARGIN = 0.005f;

    private final Activity mActivity;
    private final PlayerView mView;
    // What fades out while a trailer plays (the clock, over its corner).
    private final View[] mArtwork;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Background.Tasks mExecutor = new Background.Tasks(1);
    private ExoPlayer mPlayer;
    private MetaItem mItem;
    // Which title is current, so a slow lookup for an earlier one is dropped.
    private int mGeneration;
    // The narrowest bars seen so far in its frames, and how many frames have said.
    private float mBarTop, mBarBottom;
    private int mSamples;
    private long mFirstFrameAt;
    private boolean mShown;
    private Bitmap mSample;
    private int[] mPixels;

    HeroTrailer(Activity activity, PlayerView view, View... artwork) {
        mActivity = activity;
        mView = view;
        mArtwork = artwork;
    }

    /** The title now in the hero; null clears it. */
    void show(MetaItem item) {
        stop();
        mItem = item;
        // Not for channels, stations or podcasts, nor over radio or a podcast playing.
        if (item == null || item.id.startsWith(HomeRows.LIVE_PREFIX) || AudioRows.isAudio(item)
                || AudioPlayer.get(mActivity).isPlaying()
                || !Settings.bool(mActivity, Settings.HOME_TRAILERS)) {
            return;
        }
        int generation = mGeneration;
        long delay = Settings.integer(mActivity, Settings.TRAILER_DELAY) * 1000L;
        mHandler.postDelayed(() -> mExecutor.execute(() -> load(item, generation)), delay);
    }

    private void load(MetaItem item, int generation) {
        try {
            String trailer = item.trailer;
            if (trailer.isEmpty()) {
                trailer = Metas.fetch(Stremio.client(mActivity), Stremio.addons(mActivity),
                        item.type, item.id).meta.trailer;
            }
            String id = Youtube.videoId(trailer);
            if (id.isEmpty() || generation != mGeneration) return;
            Youtube.Streams streams = Youtube.resolve(mActivity, id, MAX_HEIGHT);
            mHandler.post(() -> {
                if (generation == mGeneration && !mActivity.isFinishing()) play(streams);
            });
        } catch (Exception e) {
            // No trailer, then: the backdrop stays.
        }
    }

    private void play(Youtube.Streams streams) {
        mPlayer = Players.trailer(mActivity, streams);
        if (Settings.bool(mActivity, Settings.TRAILER_MUTED)) mPlayer.setVolume(0f);
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                if (mFirstFrameAt > 0) return;
                mFirstFrameAt = SystemClock.elapsedRealtime();
                sample();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) stop();
            }
        });
        mView.setPlayer(mPlayer);
        mPlayer.setPlayWhenReady(true);
        mPlayer.prepare();
    }

    /** Looks at the frame showing for black bars; shows the trailer once they're known. */
    private void sample() {
        if (mPlayer == null) return;
        if (mView.getVideoSurfaceView() instanceof TextureView) {
            TextureView texture = (TextureView) mView.getVideoSurfaceView();
            if (mSample == null) {
                mSample = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
                mPixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
            }
            float[] bars = texture.isAvailable() ? Letterbox.bars(pixels(texture), SAMPLE_WIDTH, SAMPLE_HEIGHT) : null;
            // Only when the video fills the window: one of another shape has no bars burned in.
            if (bars != null && Math.abs(texture.getHeight() - mView.getHeight()) <= mView.getHeight() / 50) {
                mBarTop = mSamples == 0 ? bars[0] : Math.min(mBarTop, bars[0]);
                mBarBottom = mSamples == 0 ? bars[1] : Math.min(mBarBottom, bars[1]);
                mSamples++;
                if (mShown) crop();
            }
        }
        long since = SystemClock.elapsedRealtime() - mFirstFrameAt;
        if (!mShown && (mSamples >= SAMPLES_AGREED || since >= SHOW_BY_MS)) reveal();
        if (since < SAMPLE_FOR_MS) mHandler.postDelayed(this::sample, SAMPLE_EVERY_MS);
    }

    private int[] pixels(TextureView texture) {
        texture.getBitmap(mSample);
        mSample.getPixels(mPixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        return mPixels;
    }

    private void crop() {
        if (!(mView.getParent() instanceof EdgeFadeFrame)) return;
        float top = mSamples == 0 || mBarTop <= 0 ? 0 : mBarTop + CROP_MARGIN;
        float bottom = mSamples == 0 || mBarBottom <= 0 ? 0 : mBarBottom + CROP_MARGIN;
        if (((EdgeFadeFrame) mView.getParent()).setCrop(top, bottom)) {
            Log.i(TAG, String.format(java.util.Locale.ROOT, "Black bars: %.3f top, %.3f bottom (%d frames)",
                    top, bottom, mSamples));
        }
    }

    private void reveal() {
        mShown = true;
        crop();
        for (View v : mArtwork) v.animate().alpha(0f).setDuration(400).start();
        mView.animate().alpha(1f).setStartDelay(400).setDuration(500).start();
    }

    void stop() {
        mGeneration++;
        mFirstFrameAt = 0;
        mSamples = 0;
        mShown = false;
        if (mView.getParent() instanceof EdgeFadeFrame) ((EdgeFadeFrame) mView.getParent()).setCrop(0, 0);
        mHandler.removeCallbacksAndMessages(null);
        mView.animate().cancel();
        mView.setAlpha(0f);
        if (mPlayer != null) {
            for (View v : mArtwork) v.animate().alpha(1f).setStartDelay(0).setDuration(300).start();
            mView.setPlayer(null);
            mPlayer.release();
            mPlayer = null;
        }
    }

    void destroy() {
        stop();
        mExecutor.shutdownNow();
    }
}
