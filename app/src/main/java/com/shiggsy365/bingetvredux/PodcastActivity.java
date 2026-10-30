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
 * A podcast's page: its artwork, notes and Subscribe on the left, its episodes on the right,
 * newest first. OK plays an episode (from where it was left); Menu marks it played or not.
 */
public class PodcastActivity extends Activity {
    private static final String EXTRA_FEED = "feed";

    private String mFeedUrl;
    private Podcasts.Feed mFeed;
    private TextView mSubscribe, mStatus;
    private final List<AudioPlayer.Item> mEpisodes = new ArrayList<>();
    private final EpisodeAdapter mAdapter = new EpisodeAdapter();
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
        ((ImageView) findViewById(R.id.artwork)).setClipToOutline(true);
        VerticalGridView list = findViewById(R.id.episodes);
        list.setAdapter(mAdapter);
        list.setVerticalSpacing(Math.round(4 * getResources().getDisplayMetrics().density));
        // What's known before the feed arrives (from the card that opened it).
        Podcasts.Podcast known = Podcasts.podcast(Podcasts.PODCAST_PREFIX + mFeedUrl);
        if (known != null) showPodcast(known);
        showSubscribed();
        mSubscribe.setOnClickListener(v -> {
            Podcasts.Podcast p = mFeed != null ? mFeed.podcast : known;
            if (p == null) return;
            boolean now = Podcasts.toggleSubscription(this, p);
            showSubscribed();
            Toast.makeText(this, now ? "Subscribed to " + p.title : "Unsubscribed", Toast.LENGTH_SHORT).show();
        });
        mSubscribe.requestFocus();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from Now Playing: what's been played since.
        mAdapter.notifyDataSetChanged();
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
                if (loaded == null) {
                    mStatus.setText(message);
                    return;
                }
                mFeed = loaded;
                showPodcast(loaded.podcast);
                mEpisodes.clear();
                mEpisodes.addAll(loaded.episodes);
                mAdapter.notifyDataSetChanged();
                mStatus.setText(mEpisodes.isEmpty() ? "No episodes" : mEpisodes.size() + " episodes"
                        + "   ·   Menu: mark played");
            });
        });
    }

    private void showPodcast(Podcasts.Podcast p) {
        ((TextView) findViewById(R.id.title)).setText(p.title);
        ((TextView) findViewById(R.id.author)).setText(p.author);
        ((TextView) findViewById(R.id.notes)).setText(p.description);
        Images.load(findViewById(R.id.artwork), p.artwork);
        Images.fadeSoft(findViewById(R.id.backdrop), p.artwork);
    }

    private void showSubscribed() {
        mSubscribe.setText(Podcasts.isSubscribed(this, mFeedUrl) ? "✓  Subscribed" : "+  Subscribe");
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
            v.setOnClickListener(x -> play(e));
            v.setOnKeyListener((x, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    Podcasts.Progress now = Podcasts.progress(PodcastActivity.this, e.id);
                    boolean played = now == null || !now.played;
                    Podcasts.setPlayed(PodcastActivity.this, e, played);
                    notifyItemChanged(holder.getBindingAdapterPosition());
                    PodcastSync.pushSoon(PodcastActivity.this);
                }
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mEpisodes.size();
        }
    }
}
