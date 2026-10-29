package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.Streams;
import com.shiggsy365.bingetvredux.stremio.Subtitles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plays a film or episode from its streams (see Playback). If a stream fails or doesn't start,
 * the next is tried. The subtitles found for it can be picked with the controls' subtitle
 * button. Back stops playback.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoPlayerActivity extends Activity {
    private static final String TAG = "VideoPlayer";
    // A debrid link can take a while to start, but not this long.
    private static final long START_TIMEOUT_MS = 45_000;

    private PlayerView mPlayerView;
    private View mInfo;
    private TextView mInfoStream;
    private ExoPlayer mPlayer;
    private Playback mPlayback;
    private int mIndex;
    private boolean mStarted;
    private long mResumePosition;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStartTimeout = () -> failOver("didn't start");

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
        mInfo = findViewById(R.id.info);
        mInfoStream = findViewById(R.id.info_stream);
        ((TextView) findViewById(R.id.info_title)).setText(mPlayback.subtitle == null
                ? mPlayback.title : mPlayback.title + "  ·  " + mPlayback.subtitle);
        mPlayerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility -> mInfo.setVisibility(visibility));
        mIndex = mPlayback.start;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mPlayback != null && mPlayer == null) play(mIndex, mResumePosition);
    }

    @Override
    protected void onStop() {
        if (mPlayer != null && mStarted) mResumePosition = mPlayer.getCurrentPosition();
        release();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void play(int index, long position) {
        release();
        mIndex = index;
        mStarted = false;
        Stream stream = mPlayback.streams.get(index);
        mInfoStream.setText(stream.addon + "  ·  " + String.join("  ·  ", Streams.tags(stream)));
        Log.i(TAG, "Stream " + (index + 1) + " of " + mPlayback.streams.size() + ": "
                + String.join(" ", Streams.tags(stream)));
        mPlayer = Players.build(this, stream.headers);
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !mStarted) {
                    mStarted = true;
                    mHandler.removeCallbacks(mStartTimeout);
                } else if (state == Player.STATE_ENDED) {
                    finish();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Stream error: " + error.getErrorCodeName(), error);
                if (mStarted) {
                    // Picks up where it got to on the next stream.
                    mResumePosition = mPlayer.getCurrentPosition();
                }
                failOver(error.getErrorCodeName());
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(stream.url))
                .setSubtitleConfigurations(subtitles(mPlayback.subtitles));
        mPlayer.setMediaItem(item.build(), position);
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setPlayWhenReady(true);
        mPlayer.prepare();
        mHandler.removeCallbacks(mStartTimeout);
        mHandler.postDelayed(mStartTimeout, START_TIMEOUT_MS);
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
        long position = mStarted ? mResumePosition : 0;
        play(next, position);
    }

    private void release() {
        mHandler.removeCallbacks(mStartTimeout);
        if (mPlayer != null) {
            mPlayerView.setPlayer(null);
            mPlayer.release();
            mPlayer = null;
        }
    }

    private static List<MediaItem.SubtitleConfiguration> subtitles(List<Subtitles.Track> tracks) {
        List<MediaItem.SubtitleConfiguration> out = new ArrayList<>();
        for (Subtitles.Track t : tracks) {
            String path = Uri.parse(t.url).getPath();
            path = path == null ? "" : path.toLowerCase(Locale.ROOT);
            String mime = path.endsWith(".vtt") ? MimeTypes.TEXT_VTT
                    : path.endsWith(".ass") || path.endsWith(".ssa") ? MimeTypes.TEXT_SSA
                    : MimeTypes.APPLICATION_SUBRIP;
            out.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
                    .setMimeType(mime)
                    .setLanguage(t.lang)
                    .setLabel(t.addon)
                    .setSelectionFlags(0)
                    .build());
        }
        return out;
    }
}
