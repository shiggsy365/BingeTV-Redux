package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;


/**
 * Home's trailers: when focus rests on a title for a few seconds (the profile's setting), its
 * YouTube trailer plays in a window at the top right, with or without sound: as it's about to
 * start, the artwork fades to black, then the trailer fades in. Moving on stops it and brings
 * the artwork back. A title whose catalog entry doesn't name a trailer is looked up in full
 * first.
 */
final class HeroTrailer {
    private static final int MAX_HEIGHT = 720;

    private final Activity mActivity;
    private final PlayerView mView;
    // The artwork that fades out while a trailer plays.
    private final View[] mArtwork;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Background.Tasks mExecutor = new Background.Tasks(1);
    private ExoPlayer mPlayer;
    private MetaItem mItem;
    // Which title is current, so a slow lookup for an earlier one is dropped.
    private int mGeneration;

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
                for (View v : mArtwork) v.animate().alpha(0f).setDuration(400).start();
                mView.animate().alpha(1f).setStartDelay(400).setDuration(500).start();
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
