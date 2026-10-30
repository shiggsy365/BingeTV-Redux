package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.metadata.icy.IcyInfo;
import androidx.media3.session.MediaSession;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Radio and podcasts: one player for the whole app, so it keeps playing while you browse (the
 * web server's service keeps the app running). The remote's media keys work on any screen
 * through its media session. A podcast's place is saved as it plays (see Podcasts); a video
 * starting stops it.
 */
@OptIn(markerClass = UnstableApi.class)
final class AudioPlayer {
    private static final String TAG = "AudioPlayer";
    private static final long SAVE_EVERY_MS = 15_000;
    static final String RADIO = "radio", PODCAST = "podcast";

    /** Something to play: a radio station or a podcast episode. */
    static final class Item {
        final String kind;
        /** A station's id, or an episode's media URL (how gPodder knows episodes). */
        final String id;
        final String title;
        /** The station's country and tags, or the podcast's name. */
        final String subtitle;
        final String artwork;
        final String url;
        /** For an episode: its podcast's feed, and its date (ms) and length (s) if known. */
        final String feedUrl;
        final long published;
        final long durationS;

        Item(String kind, String id, String title, String subtitle, String artwork, String url,
                String feedUrl, long published, long durationS) {
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
            this.url = url;
            this.feedUrl = feedUrl == null ? "" : feedUrl;
            this.published = published;
            this.durationS = durationS;
        }

        boolean isRadio() {
            return RADIO.equals(kind);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("kind", kind).put("id", id).put("title", title)
                    .put("subtitle", subtitle).put("artwork", artwork).put("url", url)
                    .put("feedUrl", feedUrl).put("published", published).put("duration", durationS);
        }

        static Item fromJson(JSONObject o) {
            return new Item(o.optString("kind"), o.optString("id"), o.optString("title"),
                    o.optString("subtitle"), o.optString("artwork"), o.optString("url"),
                    o.optString("feedUrl"), o.optLong("published"), o.optLong("duration"));
        }
    }

    private static AudioPlayer sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> mListeners = new ArrayList<>();
    private ExoPlayer mPlayer;
    private MediaSession mSession;
    private Item mItem;
    // A station's current song (its stream's ICY title), "" when it sends none.
    private String mNowPlaying = "";
    private String mError = "";
    private final Runnable mSave = new Runnable() {
        @Override
        public void run() {
            saveProgress(false);
            mHandler.postDelayed(this, SAVE_EVERY_MS);
        }
    };

    private AudioPlayer(Context context) {
        mContext = context.getApplicationContext();
    }

    static synchronized AudioPlayer get(Context context) {
        if (sInstance == null) sInstance = new AudioPlayer(context);
        return sInstance;
    }

    /** Whether anything is loaded (playing or paused); static so screens can ask cheaply. */
    static boolean active() {
        return sInstance != null && sInstance.mItem != null;
    }

    /** Before a video or live TV plays: radio stops, a podcast pauses where it is. */
    static void yieldToVideo() {
        if (!active()) return;
        if (sInstance.mItem.isRadio()) sInstance.stop();
        else sInstance.pause();
    }

    void addListener(Runnable listener) {
        mListeners.add(listener);
    }

    void removeListener(Runnable listener) {
        mListeners.remove(listener);
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(mListeners)) r.run();
    }

    Item item() {
        return mItem;
    }

    String nowPlaying() {
        return mNowPlaying;
    }

    String error() {
        return mError;
    }

    boolean isPlaying() {
        return mPlayer != null && mPlayer.isPlaying();
    }

    /** Playing or about to (loading, buffering). */
    boolean playWhenReady() {
        return mPlayer != null && mPlayer.getPlayWhenReady();
    }

    boolean buffering() {
        return mPlayer != null && mPlayer.getPlaybackState() == Player.STATE_BUFFERING;
    }

    long position() {
        return mPlayer == null ? 0 : mPlayer.getCurrentPosition();
    }

    /** The length in ms, or 0 when unknown (radio). */
    long duration() {
        if (mPlayer == null) return 0;
        long d = mPlayer.getDuration();
        return d == C.TIME_UNSET ? 0 : d;
    }

    void play(Item item, long startMs) {
        saveProgress(false);
        mItem = item;
        mNowPlaying = "";
        mError = "";
        ensurePlayer();
        MediaMetadata meta = new MediaMetadata.Builder().setTitle(item.title)
                .setArtist(item.subtitle)
                .setArtworkUri(item.artwork.isEmpty() ? null : android.net.Uri.parse(item.artwork))
                .build();
        mPlayer.setMediaItem(new MediaItem.Builder().setUri(item.url).setMediaId(item.id)
                .setMediaMetadata(meta).build(), Math.max(0, startMs));
        mPlayer.prepare();
        mPlayer.play();
        if (item.isRadio()) Radio.clicked(item.id);
        Log.i(TAG, "Playing " + item.kind + " " + item.title);
        changed();
    }

    void togglePause() {
        if (mPlayer == null || mItem == null) return;
        if (mPlayer.getPlayWhenReady()) {
            pause();
        } else {
            // A station paused for long falls behind live: start it again.
            if (mItem.isRadio()) mPlayer.seekToDefaultPosition();
            if (mPlayer.getPlaybackState() == Player.STATE_IDLE) mPlayer.prepare();
            mPlayer.play();
        }
    }

    void pause() {
        if (mPlayer == null) return;
        mPlayer.pause();
        saveProgress(false);
    }

    void seekBy(long ms) {
        if (mPlayer == null || mItem == null || mItem.isRadio()) return;
        long to = Math.max(0, mPlayer.getCurrentPosition() + ms);
        long d = duration();
        if (d > 0) to = Math.min(to, d - 1_000);
        mPlayer.seekTo(to);
    }

    void stop() {
        saveProgress(false);
        mHandler.removeCallbacks(mSave);
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.clearMediaItems();
        }
        mItem = null;
        mNowPlaying = "";
        changed();
        PodcastSync.pushSoon(mContext);
    }

    private void ensurePlayer() {
        if (mPlayer != null) return;
        mPlayer = Players.build(mContext);
        mPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean playing) {
                mHandler.removeCallbacks(mSave);
                if (playing) mHandler.postDelayed(mSave, SAVE_EVERY_MS);
                else saveProgress(false);
                changed();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && mItem != null && !mItem.isRadio()) {
                    saveProgress(true);
                    Item ended = mItem;
                    mItem = null;
                    Log.i(TAG, "Finished " + ended.title);
                    PodcastSync.pushSoon(mContext);
                }
                changed();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.w(TAG, "Playback failed: " + error.getMessage());
                mError = mItem != null && mItem.isRadio() ? "This station isn't broadcasting right now."
                        : "This episode couldn't be played.";
                changed();
            }

            @Override
            public void onMetadata(@NonNull Metadata metadata) {
                for (int i = 0; i < metadata.length(); i++) {
                    if (metadata.get(i) instanceof IcyInfo) {
                        String title = ((IcyInfo) metadata.get(i)).title;
                        mNowPlaying = title == null ? "" : title.trim();
                        changed();
                    }
                }
            }
        });
        mSession = new MediaSession.Builder(mContext, mPlayer).setId("bingetv-audio").build();
    }

    /** A podcast's place, for resuming it here and on other devices (via the sync server). */
    private void saveProgress(boolean finished) {
        if (mItem == null || mItem.isRadio() || mPlayer == null) return;
        long duration = duration();
        long position = finished ? duration : mPlayer.getCurrentPosition();
        if (position <= 0 && !finished) return;
        Podcasts.saveProgress(mContext, mItem, position, duration, finished);
    }
}
