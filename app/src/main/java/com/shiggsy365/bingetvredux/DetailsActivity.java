package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;
import com.shiggsy365.bingetvredux.stremio.StremioException;
import com.shiggsy365.bingetvredux.stremio.Video;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A film's or show's info page. It opens with what the poster already knew and fills in once
 * the addons answer: details, cast and, for a show, its seasons and episodes (with what's been
 * watched). Play resumes where it was left, or plays the next episode; Start over ignores the
 * resume point. Films and seasons can be marked watched, and titles added to the MDBList
 * watchlist.
 */
public class DetailsActivity extends Activity {
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_POSTER = "poster";
    private static final String EXTRA_BACKGROUND = "background";
    private static final String EXTRA_LOGO = "logo";
    private static final String EXTRA_DESCRIPTION = "description";

    private ImageView mBackdrop;
    private ImageView mLogo;
    private TextView mTitle;
    private TextView mMeta;
    private TextView mDescription;
    private TextView mPeople;
    private TextView mStatus;
    private TextView mPlay;
    private TextView mStartOver;
    private TextView mWatched;
    private TextView mWatchlist;
    private TextView mTrailer;
    private HorizontalGridView mSeasons;
    private HorizontalGridView mEpisodes;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private String mType;
    private String mId;
    private MetaItem mItem;
    private List<Integer> mSeasonNumbers = new ArrayList<>();
    private int mSeason;
    private List<Video> mSeasonEpisodes = new ArrayList<>();
    private final String mToday = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    // What Play plays, and from where.
    private Video mPlayVideo;
    private long mPlayFrom;

    public static Intent intent(Context context, MetaItem item) {
        return new Intent(context, DetailsActivity.class)
                .putExtra(EXTRA_TYPE, item.type)
                .putExtra(EXTRA_ID, item.id)
                .putExtra(EXTRA_NAME, item.name)
                .putExtra(EXTRA_POSTER, item.poster)
                .putExtra(EXTRA_BACKGROUND, item.background)
                .putExtra(EXTRA_LOGO, item.logo)
                .putExtra(EXTRA_DESCRIPTION, item.description);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.details_activity);
        mBackdrop = findViewById(R.id.backdrop);
        mLogo = findViewById(R.id.logo);
        mTitle = findViewById(R.id.title);
        mMeta = findViewById(R.id.meta);
        mDescription = findViewById(R.id.description);
        mPeople = findViewById(R.id.people);
        mStatus = findViewById(R.id.status);
        mPlay = findViewById(R.id.play);
        mStartOver = findViewById(R.id.start_over);
        mWatched = findViewById(R.id.watched);
        mWatchlist = findViewById(R.id.watchlist);
        mTrailer = findViewById(R.id.trailer);
        mSeasons = findViewById(R.id.seasons);
        mEpisodes = findViewById(R.id.episodes);

        Intent in = getIntent();
        mType = in.getStringExtra(EXTRA_TYPE);
        mId = in.getStringExtra(EXTRA_ID);
        String background = in.getStringExtra(EXTRA_BACKGROUND);
        Images.fade(mBackdrop, background == null || background.isEmpty()
                ? in.getStringExtra(EXTRA_POSTER) : background);
        showTitle(in.getStringExtra(EXTRA_NAME), in.getStringExtra(EXTRA_LOGO));
        mDescription.setText(in.getStringExtra(EXTRA_DESCRIPTION));
        mStatus.setText("Loading…");
        mWatched.setVisibility(View.GONE);

        mPlay.setOnClickListener(v -> play(mPlayFrom, !"auto".equals(
                Settings.string(this, Settings.PLAY_BUTTON))));
        findViewById(R.id.streams).setOnClickListener(v -> play(mPlayFrom, true));
        mStartOver.setOnClickListener(v -> play(0, false));
        mWatched.setOnClickListener(v -> toggleWatched());
        mWatchlist.setOnClickListener(v -> toggleWatchlist());
        mTrailer.setOnClickListener(v -> TrailerActivity.start(this, mItem.trailer, mItem.name));
        mPlay.requestFocus();

        int margin = getResources().getDimensionPixelSize(R.dimen.screen_margin);
        for (HorizontalGridView grid : new HorizontalGridView[]{mSeasons, mEpisodes}) {
            grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE);
            grid.setWindowAlignmentOffset(margin);
            grid.setWindowAlignmentOffsetPercent(0f);
            grid.setItemAlignmentOffsetPercent(0f);
        }
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the player: progress and watched marks have moved on.
        if (mItem != null) {
            updatePlay();
            updateWatched();
            RecyclerView.Adapter<?> episodes = mEpisodes.getAdapter();
            if (episodes != null) episodes.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        mExecutor.execute(() -> {
            try {
                Metas.Found found = Metas.fetch(Stremio.client(this), Stremio.addons(this),
                        mType, mId);
                boolean watchlist = false;
                if (Mdblist.enabled(this)) {
                    try {
                        Mdblist.watchlist(this, false);
                        watchlist = true;
                    } catch (Mdblist.MdblistException ignored) {
                        // The button just stays hidden.
                    }
                }
                boolean showWatchlist = watchlist;
                runOnUiThread(() -> show(found.meta, showWatchlist));
            } catch (StremioException e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) mStatus.setText(e.getMessage());
                });
            }
        });
    }

    private void showTitle(String name, String logo) {
        mTitle.setText(name);
        boolean hasLogo = logo != null && !logo.isEmpty();
        mTitle.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
        mLogo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
        Images.fade(mLogo, logo);
    }

    private void show(MetaItem meta, boolean watchlist) {
        if (isDestroyed()) return;
        mItem = meta;
        mStatus.setVisibility(View.GONE);
        if (!meta.background.isEmpty()) Images.fade(mBackdrop, meta.background);
        showTitle(meta.name, meta.logo);
        showItemDetails();
        List<String> people = new ArrayList<>();
        List<MetaItem.Person> cast = meta.people(MetaItem.CAST);
        if (!cast.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (MetaItem.Person p : cast.subList(0, Math.min(5, cast.size()))) names.add(p.name);
            people.add("Starring " + String.join(", ", names));
        }
        List<MetaItem.Person> directors = meta.people(MetaItem.DIRECTOR);
        if (!directors.isEmpty()) people.add("Directed by " + directors.get(0).name);
        mPeople.setText(String.join("   ·   ", people));
        mTrailer.setVisibility(meta.trailer.isEmpty() ? View.GONE : View.VISIBLE);
        mWatchlist.setVisibility(watchlist ? View.VISIBLE : View.GONE);
        updateWatchlist();

        mSeasonNumbers = meta.seasons();
        if (meta.hasSeasons() && !mSeasonNumbers.isEmpty()) {
            mSeasons.setVisibility(View.VISIBLE);
            mEpisodes.setVisibility(View.VISIBLE);
            mSeasons.setAdapter(new SeasonAdapter());
        }
        updatePlay();
        if (meta.hasSeasons() && !mSeasonNumbers.isEmpty()) {
            showSeason(mPlayVideo != null && mSeasonNumbers.contains(mPlayVideo.season)
                    ? mPlayVideo.season : mSeasonNumbers.get(0));
        }
        mWatched.setVisibility(View.VISIBLE);
        updateWatched();
    }

    /** The details line and description of the title itself. */
    private void showItemDetails() {
        mMeta.setText(HomeActivity.metaLine(mItem));
        mDescription.setText(mItem.description);
    }

    // ---- Play ----

    /**
     * Works out what Play plays: a film or episode part-watched (resumed); for a show, else the
     * episode after the last one watched, else the first.
     */
    private void updatePlay() {
        WatchStore store = WatchStore.get(this);
        mPlayVideo = null;
        mPlayFrom = 0;
        if (!mItem.hasSeasons()) {
            WatchStore.Row row = store.row(mItem.id);
            if (row != null && row.positionMs > 0 && !row.watched) mPlayFrom = row.positionMs;
            mPlay.setText(mPlayFrom > 0 ? "Resume from " + clock(mPlayFrom) : "Play");
            mStartOver.setVisibility(mPlayFrom > 0 ? View.VISIBLE : View.GONE);
            return;
        }
        WatchStore.Row resume = store.resumeFor(mItem.id);
        if (resume != null) mPlayVideo = mItem.video(resume.entry.videoId);
        if (mPlayVideo != null) {
            mPlayFrom = resume.positionMs;
        } else {
            mPlayVideo = nextUnwatched(store.watchedEpisodes(mItem.id));
        }
        if (mPlayVideo == null) {
            mPlay.setText("Play");
        } else {
            mPlay.setText((mPlayFrom > 0 ? "Resume " : "Play ") + episodeCode(mPlayVideo)
                    + (mPlayFrom > 0 ? " from " + clock(mPlayFrom) : ""));
        }
        mStartOver.setVisibility(mPlayFrom > 0 ? View.VISIBLE : View.GONE);
    }

    /** The episode after the furthest one watched (if it has aired), else the first. */
    private Video nextUnwatched(Set<String> watched) {
        List<Video> episodes = new ArrayList<>();
        for (int season : mSeasonNumbers) if (season != 0) episodes.addAll(mItem.episodes(season));
        int last = -1;
        for (int i = 0; i < episodes.size(); i++) {
            Video v = episodes.get(i);
            if (watched.contains(v.season + ":" + v.episode)) last = i;
        }
        for (int i = last + 1; i < episodes.size(); i++) {
            Video v = episodes.get(i);
            if (!watched.contains(v.season + ":" + v.episode)) {
                return v.isReleased(mToday) ? v : null;
            }
        }
        return last < 0 && !episodes.isEmpty() ? episodes.get(0) : null;
    }

    private void play(long from, boolean choose) {
        if (mItem == null) return;
        if (mItem.hasSeasons()) {
            if (mPlayVideo == null) {
                Toast.makeText(this, "No episode to play next", Toast.LENGTH_SHORT).show();
                return;
            }
            playVideo(mPlayVideo, from, choose);
            return;
        }
        Playback.start(this, new Playback(mItem, null, from, ""), choose);
    }

    private void playVideo(Video video, long from, boolean choose) {
        Playback.start(this, new Playback(mItem, video, from,
                WatchStore.get(this).bingeGroup(mItem.id, 7 * 24 * 3600_000L)), choose);
    }

    static String episodeCode(Video v) {
        if (v.season == Video.NONE || v.episode == Video.NONE) return v.title;
        return String.format(Locale.ROOT, "S%d E%d", v.season, v.episode);
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---- Watched and watchlist ----

    private boolean seasonWatched() {
        Set<String> watched = WatchStore.get(this).watchedEpisodes(mItem.id);
        boolean any = false;
        for (Video v : mSeasonEpisodes) {
            if (!v.isReleased(mToday)) continue;
            any = true;
            if (!watched.contains(v.season + ":" + v.episode)) return false;
        }
        return any;
    }

    private void updateWatched() {
        if (mItem.hasSeasons()) {
            mWatched.setText(seasonWatched() ? "Mark " + seasonName(mSeason).toLowerCase(Locale.ROOT)
                    + " unwatched" : "Mark " + seasonName(mSeason).toLowerCase(Locale.ROOT) + " watched");
        } else {
            mWatched.setText(WatchStore.get(this).isWatched(mItem.id) ? "Mark unwatched" : "Mark watched");
        }
    }

    private void toggleWatched() {
        WatchStore store = WatchStore.get(this);
        List<WatchStore.Entry> entries = new ArrayList<>();
        boolean watched;
        if (mItem.hasSeasons()) {
            watched = !seasonWatched();
            for (Video v : mSeasonEpisodes) {
                if (v.isReleased(mToday)) entries.add(new Playback(mItem, v, 0, "").entry(null));
            }
        } else {
            watched = !store.isWatched(mItem.id);
            entries.add(new Playback(mItem, null, 0, "").entry(null));
        }
        store.setWatched(entries, watched);
        updateWatched();
        updatePlay();
        RecyclerView.Adapter<?> episodes = mEpisodes.getAdapter();
        if (episodes != null) episodes.notifyDataSetChanged();
    }

    private void updateWatchlist() {
        mWatchlist.setText(Mdblist.onWatchlist(mItem.id) ? "On watchlist ✓" : "Add to watchlist");
    }

    private void toggleWatchlist() {
        boolean add = !Mdblist.onWatchlist(mItem.id);
        MetaItem item = mItem;
        mExecutor.execute(() -> {
            String error = null;
            try {
                Mdblist.setOnWatchlist(this, item, add);
            } catch (Mdblist.MdblistException e) {
                error = e.getMessage();
            }
            String message = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                updateWatchlist();
                Toast.makeText(this, message != null ? message
                        : add ? "Added to your watchlist" : "Removed from your watchlist",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ---- Seasons and episodes ----

    private void showSeason(int season) {
        mSeason = season;
        mSeasonEpisodes = mItem.episodes(season);
        mEpisodes.setAdapter(new EpisodeAdapter());
        if (mPlayVideo != null && mPlayVideo.season == season) {
            mEpisodes.setSelectedPosition(Math.max(0, mSeasonEpisodes.indexOf(mPlayVideo)));
        }
        RecyclerView.Adapter<?> seasons = mSeasons.getAdapter();
        if (seasons != null) seasons.notifyDataSetChanged();
        updateWatched();
    }

    private static String seasonName(int season) {
        return season == 0 ? "Specials" : "Season " + season;
    }

    private final class SeasonAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.season_chip, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int season = mSeasonNumbers.get(position);
            TextView chip = (TextView) holder.itemView;
            chip.setText(seasonName(season));
            chip.setSelected(season == mSeason);
            // Moving along the seasons shows each one's episodes.
            chip.setOnFocusChangeListener((v, focused) -> {
                if (focused && season != mSeason) showSeason(season);
            });
            chip.setOnClickListener(v -> mEpisodes.requestFocus());
        }

        @Override
        public int getItemCount() {
            return mSeasonNumbers.size();
        }
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.episode_card, parent, false);
            view.findViewById(R.id.card).setClipToOutline(true);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Video v = mSeasonEpisodes.get(position);
            View view = holder.itemView;
            View card = view.findViewById(R.id.card);
            boolean released = v.isReleased(mToday);
            Images.load(view.findViewById(R.id.card_image), v.thumbnail);
            ((TextView) view.findViewById(R.id.card_title)).setText(
                    (v.episode == Video.NONE ? "" : v.episode + ". ") + v.title);
            ((TextView) view.findViewById(R.id.card_subtitle)).setText(released
                    ? v.airDate() : "Coming " + v.airDate());
            view.setAlpha(released ? 1f : 0.5f);
            WatchStore.Row row = WatchStore.get(DetailsActivity.this).row(v.id);
            view.findViewById(R.id.card_watched).setVisibility(
                    row != null && row.watched ? View.VISIBLE : View.GONE);
            ProgressBar progress = view.findViewById(R.id.card_progress);
            boolean partly = row != null && row.positionMs > 0 && !row.watched;
            progress.setVisibility(partly ? View.VISIBLE : View.GONE);
            if (partly) progress.setProgress((int) (row.progress() * 1000));
            long from = partly ? row.positionMs : 0;
            card.setOnClickListener(c -> playVideo(v, from, !"auto".equals(
                    Settings.string(DetailsActivity.this, Settings.PLAY_BUTTON))));
            card.setOnLongClickListener(c -> {
                playVideo(v, from, true);
                return true;
            });
            card.setOnFocusChangeListener((c, focused) -> {
                float scale = focused ? 1.06f : 1f;
                c.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                if (focused) {
                    mMeta.setText(episodeCode(v) + (v.airDate().isEmpty() ? "" : "  ·  " + v.airDate())
                            + (v.rating > 0 ? String.format(Locale.ROOT, "  ·  ★ %.1f", v.rating) : "")
                            + (partly ? "  ·  " + clock(row.durationMs - row.positionMs) + " left" : ""));
                    mDescription.setText(v.overview.isEmpty() ? mItem.description : v.overview);
                } else if (!mEpisodes.hasFocus()) {
                    showItemDetails();
                }
            });
        }

        @Override
        public int getItemCount() {
            return mSeasonEpisodes.size();
        }
    }
}
