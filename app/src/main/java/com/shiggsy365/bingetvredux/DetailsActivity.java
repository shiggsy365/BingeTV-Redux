package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;
import com.shiggsy365.bingetvredux.stremio.Parallel;
import com.shiggsy365.bingetvredux.stremio.StremioException;
import com.shiggsy365.bingetvredux.stremio.Video;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A film's or show's info page, laid out as BingeTV's. It opens with what the poster already
 * knew and fills in once the addons answer: facts and genres, the buttons, a show's seasons and
 * episodes (with what's been watched), the cast (selecting someone searches their name) and,
 * with MDBList, similar titles. Play resumes where it was left, or plays the next episode;
 * Start over ignores the resume point. Films and seasons can be marked watched, and titles added
 * to the MDBList watchlist.
 */
public class DetailsActivity extends Activity {
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_POSTER = "poster";
    private static final String EXTRA_BACKGROUND = "background";
    private static final String EXTRA_LOGO = "logo";
    private static final String EXTRA_DESCRIPTION = "description";
    private static final int MAX_CAST = 20;
    private static final int MAX_SIMILAR = 20;
    private static final long SIMILAR_TIMEOUT_MS = 10_000;

    private ScrollView mPage;
    private ImageView mBackdrop;
    private ImageView mLogo;
    private TextView mTitle;
    private TextView mSubtitle;
    private LinearLayout mFacts;
    private LinearLayout mGenres;
    private TextView mDescription;
    private TextView mStatus;
    private TextView mPlay;
    private ImageView mStreams;
    private ImageView mStartOver;
    private ImageView mWatched;
    private ImageView mWatchlist;
    private ImageView mRemove;
    private ImageView mTrailer;
    private TextView mHint;
    private View mSeasonsSection;
    private TextView mSeasonsHeading;
    private HorizontalGridView mSeasons;
    private HorizontalGridView mEpisodes;
    private View mCastSection;
    private HorizontalGridView mCast;
    private View mSimilarSection;
    private HorizontalGridView mSimilar;

    // One thread for the page's details; the watchlist and similar titles load alongside.
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(3);
    private String mType;
    private String mId;
    private MetaItem mItem;
    private boolean mWatchlistKnown;
    private List<Integer> mSeasonNumbers = new ArrayList<>();
    private int mSeason;
    private List<Video> mSeasonEpisodes = new ArrayList<>();
    private List<MetaItem.Person> mPeople = new ArrayList<>();
    private List<MetaItem> mSimilarItems = new ArrayList<>();
    private final String mToday = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    // The episode last focused, while the focus is on it or has gone up to the buttons: Play,
    // Start over and Watched act on it (and the header shows it). Null for the season/title.
    private Video mPicked;
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
        mPage = findViewById(R.id.page);
        mBackdrop = findViewById(R.id.backdrop);
        mLogo = findViewById(R.id.logo);
        mTitle = findViewById(R.id.title);
        mSubtitle = findViewById(R.id.subtitle);
        mFacts = findViewById(R.id.facts);
        mGenres = findViewById(R.id.genres);
        mDescription = findViewById(R.id.description);
        mStatus = findViewById(R.id.status);
        mPlay = findViewById(R.id.play);
        mStreams = findViewById(R.id.streams);
        mStartOver = findViewById(R.id.start_over);
        mWatched = findViewById(R.id.watched);
        mWatchlist = findViewById(R.id.watchlist);
        mRemove = findViewById(R.id.remove);
        mTrailer = findViewById(R.id.trailer);
        mHint = findViewById(R.id.hint);
        mSeasonsSection = findViewById(R.id.seasons_section);
        mSeasonsHeading = findViewById(R.id.seasons_heading);
        mSeasons = findViewById(R.id.seasons);
        mEpisodes = findViewById(R.id.episodes);
        mCastSection = findViewById(R.id.cast_section);
        mCast = findViewById(R.id.cast);
        mSimilarSection = findViewById(R.id.similar_section);
        mSimilar = findViewById(R.id.similar);

        Intent in = getIntent();
        mType = in.getStringExtra(EXTRA_TYPE);
        mId = in.getStringExtra(EXTRA_ID);
        String background = in.getStringExtra(EXTRA_BACKGROUND);
        Images.fade(mBackdrop, background == null || background.isEmpty()
                ? in.getStringExtra(EXTRA_POSTER) : background);
        showTitle(in.getStringExtra(EXTRA_NAME), in.getStringExtra(EXTRA_LOGO));
        mDescription.setText(in.getStringExtra(EXTRA_DESCRIPTION));
        mStatus.setText("Loading…");
        setPlayLabel("Play");

        mPlay.setOnClickListener(v -> play(mPlayFrom, !"auto".equals(
                Settings.string(this, Settings.PLAY_BUTTON))));
        mStreams.setOnClickListener(v -> play(mPlayFrom, true));
        mStartOver.setOnClickListener(v -> play(0, false));
        mWatched.setOnClickListener(v -> toggleWatched());
        mWatchlist.setOnClickListener(v -> toggleWatchlist());
        mRemove.setOnClickListener(v -> removeFromHome());
        mTrailer.setOnClickListener(v -> TrailerActivity.start(this, mItem.trailer, mItem.name));
        // The buttons bring the page back to its top; the round ones say what they do.
        mPlay.setOnFocusChangeListener((v, focused) -> {
            if (focused) mPage.smoothScrollTo(0, 0);
        });
        for (View button : new View[]{mStreams, mStartOver, mWatched, mRemove, mWatchlist, mTrailer}) {
            button.setOnFocusChangeListener((v, focused) -> {
                if (focused) mPage.smoothScrollTo(0, 0);
                mHint.setText(focused ? hint(v) : "");
                if (v == mWatched && mItem != null) updateWatched();
            });
        }
        mPlay.requestFocus();
        // Down from the buttons goes to the season tabs (when there are any), not past them.
        for (View button : new View[]{mPlay, mStreams, mStartOver, mWatched, mRemove, mWatchlist, mTrailer}) {
            button.setNextFocusDownId(R.id.seasons);
        }
        // A picked episode stays picked going up through the tabs to the buttons; coming down
        // onto the tabs, or anywhere else, it's the season (or title) again.
        View actions = findViewById(R.id.actions);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> {
            if (mPicked == null || now == null || within(now, mEpisodes) || within(now, actions)) return;
            if (within(now, mSeasons) && old != null && within(old, mEpisodes)) return;
            unpick();
        });

        for (HorizontalGridView grid : new HorizontalGridView[]{mSeasons, mEpisodes, mCast, mSimilar}) {
            // The tabs only scroll once the focused one would go off screen.
            grid.setWindowAlignment(grid == mSeasons ? BaseGridView.WINDOW_ALIGN_BOTH_EDGE
                    : BaseGridView.WINDOW_ALIGN_NO_EDGE);
            grid.setWindowAlignmentOffset(grid.getPaddingStart());
            grid.setWindowAlignmentOffsetPercent(0f);
            grid.setItemAlignmentOffsetPercent(0f);
        }
        mCast.setAdapter(new CastAdapter());
        mSimilar.setAdapter(new SimilarAdapter());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the player: progress and watched marks have moved on.
        if (mItem != null) {
            updatePlay();
            updateWatched();
            notifyChanged(mEpisodes, mSeasons, mSimilar);
        }
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private static void notifyChanged(RecyclerView... grids) {
        for (RecyclerView grid : grids) {
            RecyclerView.Adapter<?> adapter = grid.getAdapter();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    // ---- Loading ----

    private void load() {
        mExecutor.execute(() -> {
            try {
                Metas.Found found = Metas.fetch(Stremio.client(this), Stremio.addons(this),
                        mType, mId);
                runOnUiThread(() -> show(found.meta));
                loadSimilar(found.meta);
            } catch (StremioException e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) mStatus.setText(e.getMessage());
                });
            }
        });
        if (Mdblist.enabled(this)) {
            // Alongside the details, so the page never waits for MDBList.
            mExecutor.execute(() -> {
                try {
                    Mdblist.watchlist(this, false);
                    runOnUiThread(() -> {
                        mWatchlistKnown = true;
                        if (mItem != null) updateWatchlist();
                    });
                } catch (Mdblist.MdblistException ignored) {
                    // The button just stays hidden.
                }
            });
        }
    }

    /**
     * MDBList's similar titles, each with its meta from the addons (as BingeTV shows them),
     * falling back to MDBList's own poster.
     */
    private void loadSimilar(MetaItem meta) {
        if (!Mdblist.enabled(this)) return;
        List<MetaItem> similar;
        try {
            Mdblist.Item item = Mdblist.item(this, meta.type, meta.id);
            if (item == null || item.similar.isEmpty()) return;
            similar = item.similar.subList(0, Math.min(MAX_SIMILAR, item.similar.size()));
        } catch (Mdblist.MdblistException e) {
            return;
        }
        List<Callable<MetaItem>> tasks = new ArrayList<>();
        for (MetaItem m : similar) {
            tasks.add(() -> Metas.fetch(Stremio.client(this), Stremio.addons(this), m.type, m.id).meta);
        }
        List<Parallel.Result<MetaItem>> results = Parallel.run(tasks, SIMILAR_TIMEOUT_MS);
        List<MetaItem> shown = new ArrayList<>();
        for (int i = 0; i < similar.size(); i++) {
            Parallel.Result<MetaItem> r = results.get(i);
            shown.add(r.ok() && r.value != null && !r.value.poster.isEmpty() ? r.value : similar.get(i));
        }
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            mSimilarItems = shown;
            mSimilarSection.setVisibility(View.VISIBLE);
            notifyChanged(mSimilar);
        });
    }

    private void showTitle(String name, String logo) {
        mTitle.setText(name);
        boolean hasLogo = logo != null && !logo.isEmpty();
        mTitle.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
        mLogo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
        Images.fade(mLogo, logo);
    }

    private void show(MetaItem meta) {
        if (isDestroyed()) return;
        mItem = meta;
        mStatus.setVisibility(View.GONE);
        if (!meta.background.isEmpty()) Images.fade(mBackdrop, meta.background);
        showTitle(meta.name, meta.logo);
        showItemDetails();
        mTrailer.setVisibility(meta.trailer.isEmpty() ? View.GONE : View.VISIBLE);
        updateWatchlist();

        mPeople = meta.people(MetaItem.CAST);
        if (mPeople.size() > MAX_CAST) mPeople = new ArrayList<>(mPeople.subList(0, MAX_CAST));
        mCastSection.setVisibility(mPeople.isEmpty() ? View.GONE : View.VISIBLE);
        notifyChanged(mCast);

        mSeasonNumbers = meta.seasons();
        boolean seasons = meta.hasSeasons() && !mSeasonNumbers.isEmpty();
        if (seasons) {
            ShowProgress.remember(meta);
            mSeasonsSection.setVisibility(View.VISIBLE);
            // A show with one season has no tabs: its episodes sit under "Episodes".
            boolean one = mSeasonNumbers.size() == 1;
            mSeasons.setVisibility(one ? View.GONE : View.VISIBLE);
            mSeasonsHeading.setText(one ? "Episodes" : "Seasons");
            mSeasons.setAdapter(new SeasonAdapter());
        }
        updatePlay();
        if (seasons) {
            showSeason(mPlayVideo != null && mSeasonNumbers.contains(mPlayVideo.season)
                    ? mPlayVideo.season : mSeasonNumbers.get(0));
        }
        mWatched.setVisibility(View.VISIBLE);
        updateWatched();
    }

    /** The header as it is for the title itself (not a focused episode). */
    private void showItemDetails() {
        mSubtitle.setVisibility(View.GONE);
        InfoChips.fill(mFacts, InfoChips.facts(mItem));
        InfoChips.fill(mGenres, InfoChips.genres(mItem));
        mDescription.setText(mItem.description);
    }

    /** The header while an episode has focus: which one, its date and rating, its plot. */
    private void showEpisodeDetails(Video v, WatchStore.Row row) {
        mSubtitle.setText(episodeCode(v) + (v.title.isEmpty() ? "" : "  ·  " + v.title));
        mSubtitle.setVisibility(View.VISIBLE);
        List<String> facts = new ArrayList<>();
        String date = longDate(v.airDate());
        if (!date.isEmpty()) facts.add(date);
        if (row != null && row.positionMs > 0 && !row.watched) {
            facts.add(clock(row.durationMs - row.positionMs) + " left");
        }
        if (v.rating > 0) facts.add(String.format(Locale.ROOT, "★ %.1f", v.rating));
        InfoChips.fill(mFacts, facts);
        mDescription.setText(v.overview.isEmpty() ? mItem.description : v.overview);
    }

    private String hint(View button) {
        if (button == mStreams) return "Choose a stream";
        if (button == mStartOver) return "Start over";
        if (button == mTrailer) return "Trailer";
        if (button == mRemove) return removeLabel();
        if (button == mWatchlist) {
            return mItem != null && Mdblist.onWatchlist(mItem.id) ? "Remove from watchlist" : "Add to watchlist";
        }
        if (button == mWatched && mItem != null) return watchedLabel();
        return "";
    }

    // ---- Play ----

    private void setPlayLabel(String text) {
        Drawable icon = getDrawable(R.drawable.ic_play);
        SpannableString label = new SpannableString("  " + text);
        if (icon != null) {
            int size = Math.round(mPlay.getTextSize() * 0.9f);
            icon.setBounds(0, 0, size, size);
            label.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE), 0, 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mPlay.setText(label);
    }

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
            setPlayLabel(mPlayFrom > 0 ? "Resume from " + clock(mPlayFrom) : "Play");
            mStartOver.setVisibility(mPlayFrom > 0 ? View.VISIBLE : View.GONE);
            updateRemove();
            return;
        }
        WatchStore.Row resume = store.resumeFor(mItem.id);
        Video resumed = resume != null ? mItem.video(resume.entry.videoId) : null;
        if (mPicked != null) {
            // The episode picked in the row, resumed if it's part-watched.
            mPlayVideo = mPicked;
            WatchStore.Row row = store.row(mPicked.id);
            if (row != null && row.positionMs > 0 && !row.watched) mPlayFrom = row.positionMs;
        } else if (resumed != null) {
            mPlayVideo = resumed;
            mPlayFrom = resume.positionMs;
        } else {
            mPlayVideo = nextUnwatched(store.watchedEpisodes(mItem.id));
        }
        if (mPlayVideo == null) {
            setPlayLabel("Play");
        } else {
            setPlayLabel((mPlayFrom > 0 ? "Resume " : "Play ") + episodeCode(mPlayVideo)
                    + (mPlayFrom > 0 ? " from " + clock(mPlayFrom) : ""));
        }
        mStartOver.setVisibility(mPlayFrom > 0 ? View.VISIBLE : View.GONE);
        updateRemove();
    }

    // ---- Continue Watching and Next Up ----

    /** The part-watched row Home's Continue Watching shows for this title; null if none. */
    private WatchStore.Row resumeRow() {
        WatchStore store = WatchStore.get(this);
        if (!mItem.hasSeasons()) {
            WatchStore.Row row = store.row(mItem.id);
            return row != null && row.positionMs > 0 && !row.watched ? row : null;
        }
        return store.resumeFor(mItem.id);
    }

    /** Whether Home's Next Up lists this show: it's being watched and has a next episode. */
    private boolean inNextUp() {
        WatchStore store = WatchStore.get(this);
        return mItem.hasSeasons() && store.hasWatchedEpisodes(mItem.id) && !store.isDismissed(mItem.id)
                && nextUnwatched(store.watchedEpisodes(mItem.id)) != null;
    }

    private String removeLabel() {
        return resumeRow() != null ? "Remove from Continue Watching" : "Remove from Next Up";
    }

    private void updateRemove() {
        boolean show = resumeRow() != null || inNextUp();
        mRemove.setVisibility(show ? View.VISIBLE : View.GONE);
        mRemove.setContentDescription(removeLabel());
        if (mRemove.hasFocus()) {
            if (show) mHint.setText(removeLabel());
            else mPlay.requestFocus();
        }
    }

    /**
     * Takes the title off Continue Watching (its resume point goes; a show leaves Next Up too)
     * or, with nothing part-watched, the show off Next Up until more of it is played.
     */
    private void removeFromHome() {
        WatchStore store = WatchStore.get(this);
        WatchStore.Row resume = resumeRow();
        String done;
        if (resume != null) {
            store.clearResume(resume.entry.videoId);
            done = "Removed from Continue Watching";
        } else {
            store.dismissShow(mItem.id);
            done = "Removed from Next Up";
        }
        Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
        updatePlay();
        notifyChanged(mEpisodes);
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

    /** "2008-01-21" as "21 January 2008". */
    private static String longDate(String iso) {
        if (iso.isEmpty()) return "";
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            return d == null ? iso : new SimpleDateFormat("d MMMM yyyy", Locale.UK).format(d);
        } catch (ParseException e) {
            return iso;
        }
    }

    // ---- Watched and watchlist ----

    private boolean seasonWatched(int season) {
        Set<String> watched = WatchStore.get(this).watchedEpisodes(mItem.id);
        boolean any = false;
        for (Video v : mItem.episodes(season)) {
            if (!v.isReleased(mToday)) continue;
            any = true;
            if (!watched.contains(v.season + ":" + v.episode)) return false;
        }
        return any;
    }

    private boolean isWatched() {
        if (mPicked != null) {
            return WatchStore.get(this).watchedEpisodes(mItem.id)
                    .contains(mPicked.season + ":" + mPicked.episode);
        }
        return mItem.hasSeasons() ? seasonWatched(mSeason) : WatchStore.get(this).isWatched(mItem.id);
    }

    private String watchedLabel() {
        String what = mPicked != null ? " " + episodeCode(mPicked)
                : mItem.hasSeasons() ? " " + seasonName(mSeason).toLowerCase(Locale.ROOT) : "";
        return (isWatched() ? "Mark" + what + " unwatched" : "Mark" + what + " watched");
    }

    private void updateWatched() {
        boolean watched = isWatched();
        mWatched.setImageTintList(android.content.res.ColorStateList.valueOf(
                watched && !mWatched.hasFocus() ? getColor(R.color.guide_accent) : 0xFFFFFFFF));
        mWatched.setContentDescription(watchedLabel());
        if (mWatched.hasFocus()) mHint.setText(watchedLabel());
    }

    private void toggleWatched() {
        WatchStore store = WatchStore.get(this);
        List<WatchStore.Entry> entries = new ArrayList<>();
        boolean watched = !isWatched();
        if (mPicked != null) {
            entries.add(new Playback(mItem, mPicked, 0, "").entry(null));
        } else if (mItem.hasSeasons()) {
            for (Video v : mSeasonEpisodes) {
                if (v.isReleased(mToday)) entries.add(new Playback(mItem, v, 0, "").entry(null));
            }
        } else {
            entries.add(new Playback(mItem, null, 0, "").entry(null));
        }
        store.setWatched(entries, watched);
        updateWatched();
        updatePlay();
        notifyChanged(mEpisodes, mSeasons);
    }

    private void updateWatchlist() {
        mWatchlist.setVisibility(mWatchlistKnown ? View.VISIBLE : View.GONE);
        boolean listed = Mdblist.onWatchlist(mItem.id);
        mWatchlist.setImageResource(listed ? R.drawable.ic_watchlist_remove : R.drawable.ic_watchlist_add);
        mWatchlist.setContentDescription(listed ? "Remove from watchlist" : "Add to watchlist");
        if (mWatchlist.hasFocus()) mHint.setText(hint(mWatchlist));
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

    private static boolean within(View view, View group) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == group) return true;
        }
        return false;
    }

    private void pick(Video v) {
        if (v == mPicked) return;
        mPicked = v;
        updatePlay();
        updateWatched();
    }

    private void unpick() {
        if (mPicked == null) return;
        mPicked = null;
        showItemDetails();
        updatePlay();
        updateWatched();
    }

    private void showSeason(int season) {
        mSeason = season;
        mSeasonEpisodes = mItem.episodes(season);
        mEpisodes.setAdapter(new EpisodeAdapter());
        if (mPlayVideo != null && mPlayVideo.season == season) {
            mEpisodes.setSelectedPosition(Math.max(0, mSeasonEpisodes.indexOf(mPlayVideo)));
        }
        // Down from the buttons lands on this season's tab, not the first one.
        if (!mSeasons.hasFocus()) mSeasons.setSelectedPosition(Math.max(0, mSeasonNumbers.indexOf(season)));
        restyleTabs();
        updateWatched();
    }

    /**
     * Restyles the season tabs on screen. (Not a redraw of the row: this runs as the focus
     * moves, which can happen while the row is laid out, and RecyclerView refuses changes then.)
     */
    private void restyleTabs() {
        for (int i = 0; i < mSeasons.getChildCount(); i++) {
            View tab = mSeasons.getChildAt(i);
            if (tab.getTag() instanceof Integer) styleTab((TextView) tab, (Integer) tab.getTag());
        }
    }

    /** The season shown is bold and white; watched seasons are dimmer than the rest. */
    private void styleTab(TextView tab, int season) {
        boolean current = season == mSeason;
        tab.setSelected(current);
        tab.setTypeface(null, current ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tab.setTextColor(current || tab.isFocused() ? 0xFFFFFFFF
                : seasonWatched(season) ? 0xFF6E6E6E : 0xFFA0A0A0);
    }

    private static String seasonName(int season) {
        return season == 0 ? "Specials" : "Season " + season;
    }

    private final class SeasonAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.season_tab, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int season = mSeasonNumbers.get(position);
            TextView tab = (TextView) holder.itemView;
            tab.setText(seasonName(season));
            tab.setTag(season);
            styleTab(tab, season);
            // Moving along the seasons shows each one's episodes.
            tab.setOnFocusChangeListener((v, focused) -> {
                if (focused && season != mSeason) showSeason(season);
                else styleTab(tab, season);
            });
            tab.setOnClickListener(v -> mEpisodes.requestFocus());
            // Up always lands on Play, not whichever button happens to be above the tab.
            tab.setNextFocusUpId(R.id.play);
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
            TextView code = view.findViewById(R.id.card_code);
            code.setText(episodeCode(v));
            code.setVisibility(v.season == Video.NONE || v.episode == Video.NONE ? View.GONE : View.VISIBLE);
            ((TextView) view.findViewById(R.id.card_title)).setText(v.title);
            ((TextView) view.findViewById(R.id.card_overview)).setText(v.overview);
            String date = longDate(v.airDate());
            ((TextView) view.findViewById(R.id.card_subtitle)).setText(released ? date : "Coming " + date);
            TextView rating = view.findViewById(R.id.card_rating);
            rating.setText(v.rating > 0 ? String.format(Locale.ROOT, "%.1f", v.rating) : "");
            rating.setVisibility(v.rating > 0 ? View.VISIBLE : View.GONE);
            view.setAlpha(released ? 1f : 0.5f);
            WatchStore.Row row = WatchStore.get(DetailsActivity.this).row(v.id);
            boolean partly = row != null && row.positionMs > 0 && !row.watched;
            Badges.ribbon(view.findViewById(R.id.card_ribbon), null, partly, row != null && row.watched);
            ProgressBar progress = view.findViewById(R.id.card_progress);
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
                if (focused) {
                    showEpisodeDetails(v, row);
                    pick(v);
                } else {
                    // Once the focus has settled: still on an episode, it has set the header.
                    c.post(() -> {
                        if (!isDestroyed() && !mEpisodes.hasFocus() && mPicked == null) showItemDetails();
                    });
                }
            });
            // Up goes to the season tabs (when there are any), then the buttons.
            card.setNextFocusUpId(R.id.seasons);
        }

        @Override
        public int getItemCount() {
            return mSeasonEpisodes.size();
        }
    }

    // ---- Cast and similar ----

    private final class CastAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.cast_item, parent, false);
            view.findViewById(R.id.cast_photo_frame).setClipToOutline(true);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MetaItem.Person person = mPeople.get(position);
            View view = holder.itemView;
            ((TextView) view.findViewById(R.id.cast_name)).setText(person.name);
            ((TextView) view.findViewById(R.id.cast_role)).setText(person.role);
            ((TextView) view.findViewById(R.id.cast_initials)).setText(
                    person.photo.isEmpty() ? initials(person.name) : "");
            Images.load(view.findViewById(R.id.cast_photo), person.photo);
            view.setOnClickListener(v -> startActivity(
                    SearchActivity.personIntent(DetailsActivity.this, person.name)));
        }

        @Override
        public int getItemCount() {
            return mPeople.size();
        }
    }

    private static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && out.length() < 2) out.append(Character.toUpperCase(part.charAt(0)));
        }
        return out.toString();
    }

    private final class SimilarAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.similar_card, parent, false);
            view.findViewById(R.id.card).setClipToOutline(true);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MetaItem item = mSimilarItems.get(position);
            View view = holder.itemView;
            View card = view.findViewById(R.id.card);
            ((TextView) view.findViewById(R.id.card_name)).setText(item.name);
            ((TextView) view.findViewById(R.id.card_title)).setText(item.name);
            Images.load(view.findViewById(R.id.card_image), item.poster);
            Badges.bind(card, item);
            card.setOnClickListener(v -> startActivity(DetailsActivity.intent(DetailsActivity.this, item)));
            card.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.06f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            });
        }

        @Override
        public int getItemCount() {
            return mSimilarItems.size();
        }
    }
}
