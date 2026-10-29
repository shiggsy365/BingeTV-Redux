package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home's trailers: when focus rests on a title for a few seconds (the profile's setting), its
 * YouTube trailer fades in over the backdrop, with or without sound. Moving on stops it. A
 * title whose catalog entry doesn't name a trailer is looked up in full first.
 */
final class HeroTrailer {
    private static final int MAX_HEIGHT = 720;

    private final Activity mActivity;
    private final PlayerView mView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private ExoPlayer mPlayer;
    private MetaItem mItem;
    // Which title is current, so a slow lookup for an earlier one is dropped.
    private int mGeneration;

    HeroTrailer(Activity activity, PlayerView view) {
        mActivity = activity;
        mView = view;
    }

    /** The title now in the hero; null clears it. */
    void show(MetaItem item) {
        stop();
        mItem = item;
        if (item == null || item.id.startsWith(HomeRows.LIVE_PREFIX)
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
                mView.animate().alpha(1f).setDuration(600).start();
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

    void stop() {
        mGeneration++;
        mHandler.removeCallbacksAndMessages(null);
        mView.animate().cancel();
        mView.setAlpha(0f);
        if (mPlayer != null) {
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
