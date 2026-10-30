package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A YouTube trailer, full screen. It closes at the end; Back closes it sooner. */
public class TrailerActivity extends Activity {
    private static final String EXTRA_VIDEO = "video";
    private static final String EXTRA_TITLE = "title";

    private PlayerView mPlayerView;
    private TextView mStatus;
    private ExoPlayer mPlayer;
    // Puts the TV in the display mode that suits the video (see FrameRateMatcher).
    private FrameRateMatcher mFrameRate;
    private Youtube.Streams mStreams;
    private long mPosition;
    private boolean mVisible;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    public static void start(Context context, String trailer, String title) {
        String id = Youtube.videoId(trailer);
        if (id.isEmpty()) return;
        context.startActivity(new Intent(context, TrailerActivity.class)
                .putExtra(EXTRA_VIDEO, id).putExtra(EXTRA_TITLE, title));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.trailer_activity);
        mPlayerView = findViewById(R.id.player_view);
        mStatus = findViewById(R.id.status);
        mStatus.setText("Loading the trailer for " + getIntent().getStringExtra(EXTRA_TITLE) + "…");
        String id = getIntent().getStringExtra(EXTRA_VIDEO);
        mExecutor.execute(() -> {
            try {
                Youtube.Streams streams = Youtube.resolve(getApplicationContext(), id, 1080);
                runOnUiThread(() -> {
                    mStreams = streams;
                    if (mVisible && mPlayer == null) play();
                });
            } catch (Youtube.YoutubeException e) {
                runOnUiThread(() -> mStatus.setText(e.getMessage()));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mVisible = true;
        if (mStreams != null && mPlayer == null) play();
    }

    @Override
    protected void onStop() {
        mVisible = false;
        if (mPlayer != null) {
            mPosition = mPlayer.getCurrentPosition();
            mPlayerView.setPlayer(null);
            if (mFrameRate != null) {
                mFrameRate.release();
                mFrameRate = null;
            }
            mPlayer.release();
            mPlayer = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void play() {
        mStatus.setVisibility(View.GONE);
        mPlayer = Players.trailer(this, mStreams);
        mFrameRate = FrameRateMatcher.attach(this, mPlayer);
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) finish();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                mStatus.setVisibility(View.VISIBLE);
                mStatus.setText("The trailer couldn't be played (" + error.getErrorCodeName() + ")");
            }
        });
        mPlayerView.setPlayer(mPlayer);
        mPlayer.seekTo(mPosition);
        mPlayer.setPlayWhenReady(true);
        mPlayer.prepare();
    }
}
