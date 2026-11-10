package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A podcast's page: its artwork, notes and round buttons on the left (Subscribe and Mark
 * listened), its episodes on the right, newest first. OK plays an episode (from where it was
 * left). Mark listened (or Menu on an episode) marks the picked episode played or not: the one
 * last focused, which stays highlighted while the buttons have the focus.
 */
public class PodcastActivity extends Activity {
    private static final String EXTRA_FEED = "feed";

    private String mFeedUrl;
    private Podcasts.Feed mFeed;
    private ImageView mSubscribe, mListened;
    private TextView mHint, mStatus;
    private final List<AudioPlayer.Item> mEpisodes = new ArrayList<>();
    private final EpisodeAdapter mAdapter = new EpisodeAdapter();
    private VerticalGridView mList;
    // The episode Mark listened is for (null: none shown).
    private AudioPlayer.Item mPicked;
    private final Background.Tasks mTasks = new Background.Tasks(1);

    static Intent intent(Context context, String feedUrl) {
        return new Intent(context, PodcastActivity.class).putExtra(EXTRA_FEED, feedUrl);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.podcast_activity);
        mFeedUrl = getIntent().getStringExtra(EXTRA_FEED);
        if (mFeedUrl == null) {
            finish();
            return;
        }
        mSubscribe = findViewById(R.id.subscribe);
        mStatus = findViewById(R.id.status);
        mListened = findViewById(R.id.listened);
        mHint = findViewById(R.id.hint);
        mListened.setOnClickListener(v -> {
            if (mPicked != null) togglePlayed(mPicked);
        });
        for (View button : new View[] {mSubscribe, mListened}) {
            button.setOnFocusChangeListener((v, focused) -> showButtons());
            button.setNextFocusDownId(R.id.episodes);
            // Until the episodes are in, so an early OK can't subscribe.
            button.setFocusable(false);
        }
        showButtons();
        ((ImageView) findViewById(R.id.artwork)).setClipToOutline(true);
        VerticalGridView list = findViewById(R.id.episodes);
        mList = list;
        list.setAdapter(mAdapter);
        list.setVerticalSpacing(Math.round(4 * getResources().getDisplayMetrics().density));
        // What's known before the feed arrives (from the card that opened it).
        Podcasts.Podcast known = Podcasts.podcast(Podcasts.PODCAST_PREFIX + mFeedUrl);
        if (known != null) showPodcast(known);
        mSubscribe.setOnClickListener(v -> {
            Podcasts.Podcast p = mFeed != null ? mFeed.podcast : known;
            if (p == null) return;
            boolean now = Podcasts.toggleSubscription(this, p);
            showButtons();
            Toast.makeText(this, now ? "Subscribed to " + p.title : "Unsubscribed", Toast.LENGTH_SHORT).show();
        });
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from Now Playing: what's been played since.
        if (mFeed != null) showEpisodes();
    }

    @Override
    protected void onDestroy() {
        mTasks.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        mTasks.execute(() -> {
            Podcasts.Feed feed = null;
            String error = null;
            try {
                feed = Podcasts.feed(mFeedUrl, true);
            } catch (IOException e) {
                error = "This podcast's feed couldn't be read (" + e.getMessage() + ").";
            }
            Podcasts.Feed loaded = feed;
            String message = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                mSubscribe.setFocusable(true);
                mListened.setFocusable(true);
                if (loaded == null) {
                    mStatus.setText(message);
                    mSubscribe.requestFocus();
                    return;
                }
                mFeed = loaded;
                showPodcast(loaded.podcast);
                mEpisodes.clear();
                mEpisodes.addAll(loaded.episodes);
                showEpisodes();
                if (mList.getAdapter().getItemCount() > 0) mList.requestFocus();
                else mSubscribe.requestFocus();
            });
        });
    }

    /** Each button's icon and colour for its state, and the focused one's name as the hint. */
    private void showButtons() {
        boolean subscribed = Podcasts.isSubscribed(this, mFeedUrl);
        boolean listened = mPicked != null && isPlayed(mPicked);
        mSubscribe.setImageResource(subscribed ? R.drawable.ic_subscribed : R.drawable.ic_subscribe);
        mSubscribe.setContentDescription(subscribed ? "Unsubscribe" : "Subscribe");
        tint(mSubscribe, subscribed);
        mListened.setContentDescription(listened ? "Mark unlistened" : "Mark listened");
        tint(mListened, listened);
        View focused = getCurrentFocus();
        mHint.setText(focused == mSubscribe || focused == mListened ? focused.getContentDescription() : "");
    }

    /** Red when on (as on the info page), white while focused (the button itself is red then). */
    private void tint(ImageView button, boolean on) {
        button.setImageTintList(android.content.res.ColorStateList.valueOf(
                on && !button.hasFocus() ? getColor(R.color.guide_accent) : 0xFFFFFFFF));
    }

    private boolean isPlayed(AudioPlayer.Item episode) {
        Podcasts.Progress p = Podcasts.progress(this, episode.id);
        return p != null && p.played;
    }

    /** Marks an episode played, or not. */
    private void togglePlayed(AudioPlayer.Item episode) {
        Podcasts.setPlayed(this, episode, !isPlayed(episode));
        PodcastSync.pushSoon(this);
        int at = mEpisodes.indexOf(episode);
        if (at >= 0) mAdapter.notifyItemChanged(at);
        showButtons();
    }

    /** Picks an episode for Mark listened, highlighting its row in place of the last one. */
    private void pick(AudioPlayer.Item episode) {
        if (episode == mPicked) return;
        AudioPlayer.Item old = mPicked;
        mPicked = episode;
        for (AudioPlayer.Item e : new AudioPlayer.Item[] {old, episode}) {
            int at = e == null ? -1 : mEpisodes.indexOf(e);
            RecyclerView.ViewHolder h = at < 0 ? null : mList.findViewHolderForAdapterPosition(at);
            if (h != null) h.itemView.setSelected(e == mPicked);
        }
        showButtons();
    }

    private void showEpisodes() {
        mAdapter.notifyDataSetChanged();
        // The newest episode is picked until another is focused.
        if (mPicked == null && !mEpisodes.isEmpty()) pick(mEpisodes.get(0));
        showButtons();
        mStatus.setText(mEpisodes.isEmpty() ? "No episodes" : mEpisodes.size() + " episodes"
                + "   ·   Menu: mark played");
    }

    private void showPodcast(Podcasts.Podcast p) {
        ((TextView) findViewById(R.id.title)).setText(p.title);
        ((TextView) findViewById(R.id.author)).setText(p.author);
        ((TextView) findViewById(R.id.notes)).setText(p.description);
        Images.load(findViewById(R.id.artwork), p.artwork);
        Images.fadeSoft(findViewById(R.id.backdrop), p.artwork);
    }

    private void play(AudioPlayer.Item episode) {
        NowPlayingActivity.play(this, episode, Podcasts.resumeAt(this, episode.id));
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.podcast_episode, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AudioPlayer.Item e = mEpisodes.get(position);
            View v = holder.itemView;
            ((TextView) v.findViewById(R.id.episode_title)).setText(e.title);
            ((TextView) v.findViewById(R.id.episode_line)).setText(
                    Podcasts.episodeLine(PodcastActivity.this, e));
            String notes = mFeed == null ? null : mFeed.notes.get(e.id);
            ((TextView) v.findViewById(R.id.episode_notes)).setText(notes);
            Podcasts.Progress p = Podcasts.progress(PodcastActivity.this, e.id);
            ProgressBar bar = v.findViewById(R.id.episode_progress);
            boolean started = p != null && !p.played && p.positionMs > 0 && p.durationMs > 0;
            bar.setVisibility(started ? View.VISIBLE : View.GONE);
            if (started) bar.setProgress((int) (p.positionMs * 1000 / p.durationMs));
            v.setAlpha(p != null && p.played ? 0.6f : 1f);
            v.setSelected(e == mPicked);
            v.setOnClickListener(x -> play(e));
            v.setOnFocusChangeListener((x, focused) -> {
                if (focused) pick(e);
            });
            v.setOnKeyListener((x, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) togglePlayed(e);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mEpisodes.size();
        }
    }
}
