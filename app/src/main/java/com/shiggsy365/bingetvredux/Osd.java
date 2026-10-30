package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.shiggsy365.bingetvredux.stremio.StreamInfo;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The players' OSD, as BingeTV's: along the top a banner (logo or title, what's playing, genres,
 * the plot while paused, the clock); along the bottom the status, times, a progress line
 * (buffered under played; Up from the buttons to seek with Left and Right), "Ends at", the
 * stream's badges, and round buttons with a caption for the focused one. It closes itself
 * after a few seconds without a key press.
 *
 * Playback, seeking and the audio, subtitle, picture and speed menus are its own; each player
 * adds its buttons (episodes, streams, channels…) with {@link #button}.
 */
@OptIn(markerClass = UnstableApi.class)
final class Osd {
    private static final long HIDE_AFTER_MS = 7_000;
    private static final long STEP_MS = 10_000, JUMP_MS = 30_000;

    // Material icons (Apache 2.0).
    static final String PREVIOUS = "M6,6h2v12H6zM9.5,12l8.5,6V6z";
    static final String NEXT = "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z";
    static final String REWIND = "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z";
    static final String FORWARD = "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z";
    static final String PLAY = "M8,5v14l11,-7z";
    static final String PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z";
    static final String STOP = "M6,6h12v12H6z";
    static final String STREAMS = "M3,10h11v2H3V10zM3,6h11v2H3V6zM3,14h7v2H3V14zM16,13v8l6,-4L16,13z";
    static final String AUDIO = "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21c2.31,0 4.2,-1.75 4.45,-4H15V6h4V3h-7z";
    static final String SUBTITLES = "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z";
    static final String PICTURE = "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z";
    static final String SPEED = "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z";
    static final String CHANNELS = "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z";
    static final String GUIDE = "M3,5v14h18V5H3zM7,7v2H5V7h2zM5,13v-2h2v2H5zM5,15h2v2H5v-2zM19,17H9v-2h10v2zM19,13H9v-2h10v2zM19,9H9V7h10v2z";
    static final String START_OVER = "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z";

    /** What the OSD needs from its player. */
    abstract static class Host {
        abstract ExoPlayer player();

        /** The Stop button. */
        abstract void stop();

        /** Live TV: the progress line is the programme's (see {@link #programme}). */
        boolean live() {
            return false;
        }

        /** The programme's {start, end} (epoch ms) for live TV; null when unknown. */
        long[] programme() {
            return null;
        }

        /** {position, duration} (ms) when it isn't the player's (catch-up); null otherwise. */
        long[] timeline() {
            return null;
        }

        /** Whether Left/Right and the skip buttons move playback. */
        boolean canSeek() {
            return !live();
        }

        /** Whether playback can pause. */
        boolean canPause() {
            return !live();
        }

        /** Moves playback; false leaves it to the player. */
        boolean seekBy(long ms) {
            return false;
        }
    }

    /** A player's own button. */
    interface Visible {
        boolean now();
    }

    private static final class Button {
        final View view;
        final Visible visible;

        Button(View view, Visible visible) {
            this.view = view;
            this.visible = visible;
        }
    }

    private final Activity mActivity;
    private final Host mHost;
    private final PlayerView mPlayerView;
    private final View mRoot;
    private final TextView mTitle, mLine, mGenres, mPlot, mClock, mStatus, mTime, mFinish, mCaption;
    private final ImageView mLogo;
    private final OsdBar mSeek;
    private final LinearLayout mBadges;
    private final LinearLayout mButtons;
    private final List<Button> mAll = new ArrayList<>();
    private ImageView mPlayPause;
    private ListPopupWindow mMenu;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHide = this::hide;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, 500);
        }
    };
    private final DateFormat mTimeFormat;
    private SubtitleDriver mSubtitles;
    private List<com.shiggsy365.bingetvredux.stremio.Subtitles.Track> mAddonSubtitles = new ArrayList<>();

    Osd(Activity activity, PlayerView playerView, Host host) {
        mActivity = activity;
        mHost = host;
        mPlayerView = playerView;
        mRoot = activity.findViewById(R.id.osd);
        mTitle = activity.findViewById(R.id.osd_title);
        mLogo = activity.findViewById(R.id.osd_logo);
        mLine = activity.findViewById(R.id.osd_line);
        mGenres = activity.findViewById(R.id.osd_genres);
        mPlot = activity.findViewById(R.id.osd_plot);
        mClock = activity.findViewById(R.id.osd_clock);
        mStatus = activity.findViewById(R.id.osd_status);
        mTime = activity.findViewById(R.id.osd_time);
        mFinish = activity.findViewById(R.id.osd_finish);
        mCaption = activity.findViewById(R.id.osd_caption);
        mSeek = activity.findViewById(R.id.osd_seek);
        mBadges = activity.findViewById(R.id.osd_badges);
        mButtons = activity.findViewById(R.id.osd_buttons);
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(activity);
        mSeek.setOnFocusChangeListener((v, focused) -> {
            if (focused) mCaption.setText("Seek  ·  Left / Right to skip 10 s");
        });
        mSeek.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                seekBy(-step(event));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekBy(step(event));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (mPlayPause != null) mPlayPause.requestFocus();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_UP;
        });
    }

    // ---- What's playing

    /** The banner: logo (or title), the line under it, genres and plot. */
    void setInfo(String title, String logo, String line, String genres, String plot) {
        boolean hasLogo = logo != null && !logo.isEmpty();
        mTitle.setText(title);
        mTitle.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
        mLogo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
        Images.load(mLogo, logo);
        mLine.setText(line);
        mLine.setVisibility(line == null || line.isEmpty() ? View.GONE : View.VISIBLE);
        mGenres.setText(genres);
        mGenres.setVisibility(genres == null || genres.isEmpty() ? View.GONE : View.VISIBLE);
        mPlot.setText(plot);
    }

    /** What draws the subtitles, and the subtitle addons' tracks for this video. */
    void setSubtitles(SubtitleDriver driver, List<com.shiggsy365.bingetvredux.stremio.Subtitles.Track> addonTracks) {
        mSubtitles = driver;
        mAddonSubtitles = addonTracks == null ? new ArrayList<>() : addonTracks;
    }

    /** A logo already loaded (live TV's channel logos). */
    void setLogo(android.graphics.Bitmap logo) {
        mLogo.setImageBitmap(logo);
        mLogo.setVisibility(logo != null ? View.VISIBLE : View.GONE);
        mTitle.setVisibility(logo != null ? View.GONE : View.VISIBLE);
    }

    /** The playing stream's badges (none for live TV). */
    void setStream(StreamInfo info) {
        if (info == null) mBadges.removeAllViews();
        else StreamCards.badges(mBadges, info);
    }

    // ---- Buttons

    /**
     * Adds a button, in order. {@code visible} is asked whenever the OSD opens (null: always);
     * returns the button.
     */
    View button(String icon, String caption, Runnable onClick, Visible visible) {
        ImageView view = (ImageView) LayoutInflater.from(mActivity).inflate(R.layout.osd_button, mButtons, false);
        view.setImageDrawable(MenuIcons.drawable(mActivity, icon, 20));
        view.setContentDescription(caption);
        view.setOnClickListener(v -> {
            scheduleHide();
            onClick.run();
        });
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) mCaption.setText(v.getContentDescription());
        });
        // Up from any button goes to the progress line, to seek.
        view.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && mSeek.getVisibility() == View.VISIBLE
                    && mSeek.isFocusable()) {
                mSeek.requestFocus();
            }
            return true;
        });
        mButtons.addView(view);
        mAll.add(new Button(view, visible));
        return view;
    }

    /** The playback buttons: back 30 s, play/pause, stop, forward 30 s (seeking ones not for live). */
    void addPlaybackButtons() {
        button(REWIND, "Back 30 s", () -> seekBy(-JUMP_MS), mHost::canSeek);
        mPlayPause = (ImageView) button(PAUSE, "Pause", this::togglePause, mHost::canPause);
        button(STOP, "Stop", mHost::stop, null);
        button(FORWARD, "Forward 30 s", () -> seekBy(JUMP_MS), mHost::canSeek);
    }

    /** Audio track, subtitles (and their size), picture size, and playback speed. */
    void addSettingsButtons() {
        button(AUDIO, "Audio", () -> menu(lastButton(AUDIO_TAG), MENU_AUDIO), null).setTag(AUDIO_TAG);
        button(SUBTITLES, "Subtitles", () -> menu(lastButton(SUBS_TAG), MENU_SUBTITLES), null).setTag(SUBS_TAG);
        button(PICTURE, "Picture size", () -> menu(lastButton(PICTURE_TAG), MENU_PICTURE), null).setTag(PICTURE_TAG);
        button(SPEED, "Speed", () -> menu(lastButton(SPEED_TAG), MENU_SPEED), mHost::canSeek).setTag(SPEED_TAG);
    }

    private static final String AUDIO_TAG = "audio", SUBS_TAG = "subs", PICTURE_TAG = "picture", SPEED_TAG = "speed";

    private View lastButton(String tag) {
        return mButtons.findViewWithTag(tag);
    }

    // ---- Showing and hiding

    boolean isShowing() {
        return mRoot.getVisibility() == View.VISIBLE;
    }

    /** Showing, or the subtitle offset is being adjusted: keys are the OSD's. */
    boolean isBusy() {
        return isShowing() || adjustingOffset();
    }

    /** Opens the OSD with the focus on play/pause, or on the progress line ({@code seeking}). */
    void show(boolean seeking) {
        boolean opening = !isShowing();
        for (Button b : mAll) {
            b.view.setVisibility(b.visible == null || b.visible.now() ? View.VISIBLE : View.GONE);
        }
        boolean canSeek = mHost.canSeek();
        mSeek.setFocusable(canSeek);
        if (opening) {
            mRoot.setAlpha(0f);
            mRoot.setVisibility(View.VISIBLE);
            mRoot.animate().alpha(1f).setDuration(180).start();
            mHandler.removeCallbacks(mTick);
            mHandler.post(mTick);
        }
        if (seeking && canSeek) mSeek.requestFocus();
        else if (opening) firstVisible().requestFocus();
        scheduleHide();
    }

    /** Play/pause when it's there, else the first button shown. */
    private View firstVisible() {
        if (mPlayPause != null && mPlayPause.getVisibility() == View.VISIBLE) return mPlayPause;
        for (Button b : mAll) if (b.view.getVisibility() == View.VISIBLE) return b.view;
        return mPlayerView;
    }

    void hide() {
        if (!isShowing()) return;
        if (mMenu != null && mMenu.isShowing()) {
            scheduleHide();
            return;
        }
        mHandler.removeCallbacks(mHide);
        mRoot.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            mRoot.setVisibility(View.GONE);
            mHandler.removeCallbacks(mTick);
            mPlayerView.requestFocus();
        }).start();
    }

    private void scheduleHide() {
        mHandler.removeCallbacks(mHide);
        Player player = mHost.player();
        // Stays open while paused.
        if (player != null && !player.getPlayWhenReady()) return;
        mHandler.postDelayed(mHide, HIDE_AFTER_MS);
    }

    void release() {
        mHandler.removeCallbacksAndMessages(null);
        if (mMenu != null) mMenu.dismiss();
    }

    /**
     * Keys for the OSD, from the activity's dispatchKeyEvent; true when used. With the OSD
     * closed: OK, Up or Down opens it; Left/Right seek; the media keys play, pause and skip.
     * Open: Back closes it, and any key keeps it open.
     */
    boolean onKey(KeyEvent event) {
        if (adjustingOffset()) return onOffsetKey(event);
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (key) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!mHost.canPause()) return true;
                if (down && event.getRepeatCount() == 0) {
                    togglePause();
                    show(false);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down && mHost.canSeek()) {
                    seekBy(key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? JUMP_MS : -JUMP_MS);
                    show(true);
                }
                return true;
            default:
                break;
        }
        if (isShowing()) {
            if (key == KeyEvent.KEYCODE_BACK) {
                if (!down) hide();
                return true;
            }
            scheduleHide();
            return false;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) show(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!mHost.canSeek()) {
                    if (down) show(false);
                    return true;
                }
                if (down) {
                    seekBy(key == KeyEvent.KEYCODE_DPAD_RIGHT ? step(event) : -step(event));
                    show(true);
                }
                return true;
            default:
                return false;
        }
    }

    // ---- Playback

    private void togglePause() {
        Player player = mHost.player();
        if (player == null) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
        update();
        scheduleHide();
    }

    /** Holding Left or Right skips further: 10 s, then 30 s, then a minute at a time. */
    private static long step(KeyEvent event) {
        int repeat = event.getRepeatCount();
        return repeat > 20 ? 60_000 : repeat > 6 ? JUMP_MS : STEP_MS;
    }

    private void seekBy(long ms) {
        if (!mHost.canSeek()) return;
        if (mHost.seekBy(ms)) {
            update();
            return;
        }
        Player player = mHost.player();
        if (player == null) return;
        long duration = player.getDuration();
        long target = player.getCurrentPosition() + ms;
        if (duration != C.TIME_UNSET) target = Math.min(target, Math.max(0, duration - 1_000));
        player.seekTo(Math.max(0, target));
        update();
    }

    // ---- The bar

    private void update() {
        mClock.setText(mTimeFormat.format(new Date()));
        Player player = mHost.player();
        if (player == null) return;
        boolean paused = !player.getPlayWhenReady();
        if (mPlayPause != null) {
            mPlayPause.setImageDrawable(MenuIcons.drawable(mActivity, paused ? PLAY : PAUSE, 20));
            mPlayPause.setContentDescription(paused ? "Play" : "Pause");
            if (mPlayPause.isFocused()) mCaption.setText(mPlayPause.getContentDescription());
        }
        mPlot.setVisibility(paused && mPlot.length() > 0 ? View.VISIBLE : View.GONE);
        float speed = player.getPlaybackParameters().speed;
        String status;
        if (player.getPlaybackState() == Player.STATE_BUFFERING) {
            status = "Buffering…";
        } else if (paused) {
            status = "Paused";
        } else {
            status = speed != 1f ? String.format(Locale.ROOT, "Playing  %.2gx", speed) : "Playing";
        }
        mStatus.setText(status);
        long[] programme = mHost.live() ? mHost.programme() : null;
        long now = System.currentTimeMillis();
        if (programme != null && programme[1] > programme[0]) {
            mTime.setText(mTimeFormat.format(new Date(programme[0])) + " – " + mTimeFormat.format(new Date(programme[1])));
            mSeek.set((now - programme[0]) / (float) (programme[1] - programme[0]), 0);
            mFinish.setText("Ends at " + mTimeFormat.format(new Date(programme[1])));
            mSeek.setVisibility(View.VISIBLE);
            return;
        }
        long[] timeline = mHost.timeline();
        long duration = timeline != null ? timeline[1] : player.getDuration();
        long position = timeline != null ? timeline[0] : player.getCurrentPosition();
        if (duration == C.TIME_UNSET || duration <= 0) {
            mTime.setText(mHost.live() ? "Live" : DetailsActivity.clock(position));
            mSeek.setVisibility(mHost.live() ? View.INVISIBLE : View.VISIBLE);
            mSeek.set(0, 0);
            mFinish.setText("");
            return;
        }
        mSeek.setVisibility(View.VISIBLE);
        mTime.setText(DetailsActivity.clock(position) + " / " + DetailsActivity.clock(duration));
        mSeek.set(position / (float) duration, timeline != null ? 0
                : player.getBufferedPosition() / (float) duration);
        long left = (long) ((duration - position) / Math.max(0.1f, speed));
        mFinish.setText("Ends at " + mTimeFormat.format(new Date(now + left)));
    }

    // ---- Menus

    private static final int MENU_AUDIO = 0, MENU_SUBTITLES = 1, MENU_PICTURE = 2, MENU_SPEED = 3;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private void menu(View anchor, int which) {
        ExoPlayer player = mHost.player();
        if (player == null || anchor == null) return;
        mHandler.removeCallbacks(mHide);
        Runnable closed = () -> {
            mMenu = null;
            scheduleHide();
            anchor.requestFocus();
        };
        List<String> labels = new ArrayList<>();
        int chosen = -1;
        if (which == MENU_SUBTITLES) {
            subtitlesMenu(anchor, closed);
        } else if (which == MENU_AUDIO) {
            List<Tracks.Group> groups = new ArrayList<>();
            List<Integer> indexes = new ArrayList<>();
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    if (g.isTrackSelected(i)) chosen = labels.size();
                    labels.add(trackLabel(g.getTrackFormat(i), C.TRACK_TYPE_AUDIO));
                    groups.add(g);
                    indexes.add(i);
                }
            }
            mMenu = ChoiceMenu.show(anchor, "Audio", labels, chosen, index -> player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters().buildUpon()
                            .setOverrideForType(new TrackSelectionOverride(
                                    groups.get(index).getMediaTrackGroup(), indexes.get(index)))
                            .build()), closed);
        } else if (which == MENU_PICTURE) {
            int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    AspectRatioFrameLayout.RESIZE_MODE_FILL};
            labels.addAll(Arrays.asList("Fit (as made)", "Zoom to fill the screen", "Stretch to fill the screen"));
            for (int i = 0; i < modes.length; i++) if (modes[i] == mPlayerView.getResizeMode()) chosen = i;
            mMenu = ChoiceMenu.show(anchor, "Picture size", labels, chosen,
                    index -> mPlayerView.setResizeMode(modes[index]), closed);
        } else {
            float speed = player.getPlaybackParameters().speed;
            for (int i = 0; i < SPEEDS.length; i++) {
                labels.add(SPEEDS[i] == 1f ? "Normal" : String.format(Locale.ROOT, "%.2gx", SPEEDS[i]));
                if (Math.abs(SPEEDS[i] - speed) < 0.01) chosen = i;
            }
            mMenu = ChoiceMenu.show(anchor, "Speed", labels, chosen,
                    index -> player.setPlaybackParameters(new PlaybackParameters(SPEEDS[index])), closed);
        }
    }

    /**
     * Off, the video's own tracks, the subtitle addons' tracks, then the offset and the style
     * (each opening a menu of its own).
     */
    private void subtitlesMenu(View anchor, Runnable closed) {
        ExoPlayer player = mHost.player();
        if (player == null || mSubtitles == null) return;
        List<String> labels = new ArrayList<>();
        List<Object> choices = new ArrayList<>();
        int chosen = -1;
        boolean textOn = !player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        labels.add("Off");
        choices.add("off");
        if (!textOn && mSubtitles.external() == null) chosen = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (textOn && g.isTrackSelected(i) && mSubtitles.external() == null) chosen = labels.size();
                labels.add(trackLabel(g.getTrackFormat(i), C.TRACK_TYPE_TEXT) + "  ·  in the video");
                choices.add(new Object[]{g, i});
            }
        }
        for (com.shiggsy365.bingetvredux.stremio.Subtitles.Track t : mAddonSubtitles) {
            if (t == mSubtitles.external()) chosen = labels.size();
            String language = t.lang.isEmpty() ? "" : new Locale(t.lang).getDisplayLanguage(Locale.UK);
            labels.add((language.isEmpty() ? "Subtitles" : language) + "  ·  " + t.addon);
            choices.add(t);
        }
        labels.add(String.format(Locale.ROOT, "Offset: %+.1f s", mSubtitles.offsetMs() / 1000f));
        choices.add("offset");
        labels.add("Style: " + SubtitleStyle.COLOURS[SubtitleStyle.colour(mActivity)] + ", "
                + SubtitleStyle.SIZES[SubtitleStyle.size(mActivity)].toLowerCase(Locale.ROOT) + ", "
                + SubtitleStyle.OUTLINES[SubtitleStyle.outline(mActivity)].toLowerCase(Locale.ROOT));
        choices.add("style");
        mMenu = ChoiceMenu.show(anchor, "Subtitles", labels, chosen, index -> {
            Object c = choices.get(index);
            if ("off".equals(c)) {
                mSubtitles.off();
            } else if ("offset".equals(c)) {
                mHandler.post(this::adjustOffset);
            } else if ("style".equals(c)) {
                mHandler.post(() -> styleMenu(anchor, closed));
            } else if (c instanceof com.shiggsy365.bingetvredux.stremio.Subtitles.Track) {
                mSubtitles.useExternal((com.shiggsy365.bingetvredux.stremio.Subtitles.Track) c);
            } else {
                Object[] track = (Object[]) c;
                mSubtitles.useEmbedded();
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(
                                ((Tracks.Group) track[0]).getMediaTrackGroup(), (Integer) track[1]))
                        .build());
            }
        }, closed);
    }

    // ---- Subtitle offset, adjusted with the picture in view

    private boolean adjustingOffset() {
        View panel = mActivity.findViewById(R.id.offset_panel);
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    /** Closes the OSD and shows the offset on its own at the top while it's adjusted. */
    private void adjustOffset() {
        View panel = mActivity.findViewById(R.id.offset_panel);
        if (panel == null || mSubtitles == null) return;
        mHandler.removeCallbacks(mHide);
        mRoot.setVisibility(View.GONE);
        mHandler.removeCallbacks(mTick);
        ((TextView) mActivity.findViewById(R.id.offset_hint)).setText(mSubtitles.external() == null
                ? "Right / Left: later or earlier by 0.1 s  ·  Up / Down: 0.5 s  ·  OK to keep\n"
                + "(the video's own subtitles can only be delayed)"
                : "Right / Left: later or earlier by 0.1 s  ·  Up / Down: 0.5 s  ·  OK to keep");
        showOffset();
        panel.setVisibility(View.VISIBLE);
    }

    private void showOffset() {
        ((TextView) mActivity.findViewById(R.id.offset_value)).setText(
                String.format(Locale.ROOT, "%+.1f s", mSubtitles.offsetMs() / 1000f));
    }

    /** Keys while the offset is being adjusted. */
    private boolean onOffsetKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        long step;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: step = 100; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: step = -100; break;
            case KeyEvent.KEYCODE_DPAD_UP: step = 500; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: step = -500; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                mActivity.findViewById(R.id.offset_panel).setVisibility(View.GONE);
                return true;
            default:
                return true;
        }
        long offset = mSubtitles.offsetMs() + step;
        if (mSubtitles.external() == null) offset = Math.max(0, offset);
        mSubtitles.setOffsetMs(offset);
        showOffset();
        return true;
    }

    /** Colour, size and outline, remembered for the whole app. */
    private void styleMenu(View anchor, Runnable closed) {
        String[][] options = {SubtitleStyle.COLOURS, SubtitleStyle.SIZES, SubtitleStyle.OUTLINES};
        String[] keys = {"colour", "size", "outline"};
        int[] current = {SubtitleStyle.colour(mActivity), SubtitleStyle.size(mActivity), SubtitleStyle.outline(mActivity)};
        List<String> labels = Arrays.asList("Colour: " + options[0][current[0]], "Size: " + options[1][current[1]],
                "Outline: " + options[2][current[2]]);
        mMenu = ChoiceMenu.show(anchor, "Subtitle style", labels, -1, which -> mHandler.post(() ->
                mMenu = ChoiceMenu.show(anchor, labels.get(which).split(":")[0], Arrays.asList(options[which]),
                        current[which], value -> {
                            SubtitleStyle.set(mActivity, keys[which], value);
                            mSubtitles.restyle();
                            mHandler.post(() -> styleMenu(anchor, closed));
                        }, closed)), closed);
    }

    /** "English · DD+ 5.1", "French (forced)". */
    private static String trackLabel(Format f, int type) {
        List<String> parts = new ArrayList<>();
        String language = f.language == null || f.language.equals("und") ? ""
                : new Locale(f.language).getDisplayLanguage(Locale.UK);
        if (!language.isEmpty()) parts.add(language);
        if (f.label != null && !f.label.isEmpty() && !f.label.equalsIgnoreCase(language)) parts.add(f.label);
        if (type == C.TRACK_TYPE_AUDIO) {
            String codec = codec(f.sampleMimeType, f.codecs);
            if (!codec.isEmpty()) parts.add(codec);
            if (f.channelCount > 0) {
                parts.add(f.channelCount == 1 ? "Mono" : f.channelCount == 2 ? "Stereo"
                        : f.channelCount == 6 ? "5.1" : f.channelCount == 8 ? "7.1" : f.channelCount + " ch");
            }
        } else if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            parts.add("forced");
        }
        return parts.isEmpty() ? "Track" : String.join("  ·  ", parts);
    }

    private static String codec(String mime, String codecs) {
        if (mime == null) return "";
        switch (mime) {
            case "audio/eac3-joc": return "DD+ Atmos";
            case "audio/eac3": return codecs != null && codecs.contains("joc") ? "DD+ Atmos" : "DD+";
            case "audio/ac3": return "DD";
            case "audio/true-hd": return "TrueHD";
            case "audio/vnd.dts": return "DTS";
            case "audio/vnd.dts.hd": return "DTS-HD";
            case "audio/vnd.dts.uhd;profile=p2": return "DTS:X";
            case "audio/mp4a-latm": return "AAC";
            case "audio/opus": return "Opus";
            case "audio/flac": return "FLAC";
            case "audio/mpeg": return "MP3";
            case "audio/raw": return "PCM";
            default: return "";
        }
    }
}
