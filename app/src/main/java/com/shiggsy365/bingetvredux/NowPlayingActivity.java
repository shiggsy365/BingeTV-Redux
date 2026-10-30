package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * What the radio or podcast player is playing, with its controls. Back leaves it playing (it
 * carries on while you browse); Stop ends it. The remote's media keys work here and anywhere.
 */
public class NowPlayingActivity extends Activity {
    private static final long TICK_MS = 500;
    private static final long PODCAST_BACK_MS = 15_000, PODCAST_FORWARD_MS = 30_000;
    // Material icons (Apache 2.0).
    static final String HEART = "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z";
    static final String HEART_OUTLINE = "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z";
    static final String LIST = "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z";

    private AudioPlayer mAudio;
    private AudioPlayer.Item mShown;
    private ImageView mArtwork, mBackdrop, mPlayPause, mFavourite;
    private TextView mKind, mTitle, mSubtitle, mNow, mTimes, mStatus, mCaption;
    private View mProgressBox;
    private ProgressBar mProgress;
    private LinearLayout mButtons;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mChanged = this::update;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    /** Plays {@code item} from {@code startMs} and shows it. */
    static void play(Activity from, AudioPlayer.Item item, long startMs) {
        AudioPlayer.get(from).play(item, startMs);
        from.startActivity(new Intent(from, NowPlayingActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.now_playing_activity);
        mAudio = AudioPlayer.get(this);
        mArtwork = findViewById(R.id.artwork);
        mArtwork.setClipToOutline(true);
        mBackdrop = findViewById(R.id.backdrop);
        mKind = findViewById(R.id.kind);
        mTitle = findViewById(R.id.title);
        mSubtitle = findViewById(R.id.subtitle);
        mNow = findViewById(R.id.now);
        mNow.setSelected(true);
        mTimes = findViewById(R.id.times);
        mStatus = findViewById(R.id.status);
        mCaption = findViewById(R.id.caption);
        mProgressBox = findViewById(R.id.progress_box);
        mProgress = findViewById(R.id.progress);
        mButtons = findViewById(R.id.buttons);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mAudio.addListener(mChanged);
        update();
        mHandler.post(mTick);
    }

    @Override
    protected void onStop() {
        mAudio.removeListener(mChanged);
        mHandler.removeCallbacks(mTick);
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) mAudio.togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) mAudio.seekBy(-PODCAST_BACK_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) mAudio.seekBy(PODCAST_FORWARD_MS);
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    /** Everything, when what's playing (or its state) changes. */
    private void update() {
        AudioPlayer.Item item = mAudio.item();
        if (item == null) {
            // Stopped, or a podcast finished.
            finish();
            return;
        }
        if (item != mShown) {
            mShown = item;
            bind(item);
        }
        mPlayPause.setImageDrawable(MenuIcons.drawable(this,
                mAudio.playWhenReady() ? Osd.PAUSE : Osd.PLAY, 22));
        mPlayPause.setContentDescription(mAudio.playWhenReady() ? "Pause" : "Play");
        if (mPlayPause.hasFocus()) mCaption.setText(mPlayPause.getContentDescription());
        String now = mAudio.nowPlaying();
        mNow.setText(now.isEmpty() ? null : "♪  " + now);
        mNow.setVisibility(now.isEmpty() ? View.GONE : View.VISIBLE);
        String error = mAudio.error();
        mStatus.setText(!error.isEmpty() ? error : mAudio.buffering() ? "Loading…"
                : mAudio.playWhenReady() ? "" : "Paused");
        updateProgress();
    }

    private void bind(AudioPlayer.Item item) {
        boolean radio = item.isRadio();
        mKind.setText(radio ? "Radio" : "Podcast");
        mTitle.setText(item.title);
        mSubtitle.setText(item.subtitle);
        mArtwork.setScaleType(radio ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        int pad = radio ? Math.round(24 * getResources().getDisplayMetrics().density) : 0;
        mArtwork.setPadding(pad, pad, pad, pad);
        Images.load(mArtwork, item.artwork);
        Images.fadeSoft(mBackdrop, item.artwork);
        mProgressBox.setVisibility(radio ? View.GONE : View.VISIBLE);
        mButtons.removeAllViews();
        if (!radio) button(Osd.REWIND, "Back 15 s", () -> mAudio.seekBy(-PODCAST_BACK_MS));
        mPlayPause = button(Osd.PAUSE, "Pause", mAudio::togglePause);
        if (!radio) button(Osd.FORWARD, "Forward 30 s", () -> mAudio.seekBy(PODCAST_FORWARD_MS));
        button(Osd.STOP, "Stop", mAudio::stop);
        if (radio) {
            boolean fav = Radio.isFavourite(this, item.id);
            mFavourite = button(fav ? HEART : HEART_OUTLINE, fav ? "Remove from favourites" : "Add to favourites",
                    () -> {
                        boolean now = Radio.toggleFavourite(this, item);
                        mFavourite.setImageDrawable(MenuIcons.drawable(this, now ? HEART : HEART_OUTLINE, 22));
                        mFavourite.setContentDescription(now ? "Remove from favourites" : "Add to favourites");
                        mCaption.setText(mFavourite.getContentDescription());
                    });
        } else if (!item.feedUrl.isEmpty()) {
            button(LIST, "All episodes", () -> startActivity(PodcastActivity.intent(this, item.feedUrl)));
        }
        mPlayPause.requestFocus();
    }

    private ImageView button(String icon, String caption, Runnable onClick) {
        ImageView view = (ImageView) LayoutInflater.from(this).inflate(R.layout.osd_button, mButtons, false);
        view.setImageDrawable(MenuIcons.drawable(this, icon, 22));
        view.setContentDescription(caption);
        view.setOnClickListener(v -> onClick.run());
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) mCaption.setText(v.getContentDescription());
        });
        mButtons.addView(view);
        return view;
    }

    private void updateProgress() {
        AudioPlayer.Item item = mAudio.item();
        if (item == null || item.isRadio()) return;
        long position = mAudio.position(), duration = mAudio.duration();
        mProgress.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
        mTimes.setText(duration > 0 ? time(position) + "  /  " + time(duration)
                + "   ·   " + time(duration - position) + " left" : time(position));
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
