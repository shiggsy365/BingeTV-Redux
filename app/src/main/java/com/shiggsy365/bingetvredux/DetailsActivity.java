package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A film's or show's info page. It opens with what the poster already knew (name, artwork,
 * description) and fills in once the addons answer: details, cast and, for a show, its
 * seasons and episodes. Focusing an episode shows its own details at the top.
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

        mPlay.setOnClickListener(v -> play(false));
        findViewById(R.id.streams).setOnClickListener(v -> play(true));
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
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        mExecutor.execute(() -> {
            try {
                Metas.Found found = Metas.fetch(Stremio.client(this), Stremio.addons(this),
                        mType, mId);
                runOnUiThread(() -> show(found.meta));
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

    private void show(MetaItem meta) {
        if (isDestroyed()) return;
        mItem = meta;
        mStatus.setText(null);
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

        mSeasonNumbers = meta.seasons();
        if (meta.hasSeasons() && !mSeasonNumbers.isEmpty()) {
            mSeasons.setVisibility(View.VISIBLE);
            mEpisodes.setVisibility(View.VISIBLE);
            mSeasons.setAdapter(new SeasonAdapter());
            showSeason(mSeasonNumbers.get(0));
            Video first = firstEpisode();
            mPlay.setText(first == null ? "Play" : "Play " + episodeCode(first));
        }
    }

    /** The details line and description of the title itself. */
    private void showItemDetails() {
        mMeta.setText(HomeActivity.metaLine(mItem));
        mDescription.setText(mItem.description);
    }

    private Video firstEpisode() {
        for (int season : mSeasonNumbers) {
            if (season == 0) continue;
            List<Video> episodes = mItem.episodes(season);
            if (!episodes.isEmpty()) return episodes.get(0);
        }
        return null;
    }

    private void play(boolean choose) {
        if (mItem != null && mItem.hasSeasons()) {
            Video first = firstEpisode();
            if (first != null) playVideo(first, choose);
            return;
        }
        startActivity(StreamsActivity.intent(this, mType, mId, mItem != null ? mItem.name
                : mTitle.getText().toString(), null, choose, backdropUrl()));
    }

    private void playVideo(Video video, boolean choose) {
        startActivity(StreamsActivity.intent(this, mType, video.id, mItem.name,
                episodeCode(video) + (video.title.isEmpty() ? "" : "  " + video.title), choose,
                backdropUrl()));
    }

    private String backdropUrl() {
        if (mItem != null && !mItem.background.isEmpty()) return mItem.background;
        return getIntent().getStringExtra(EXTRA_BACKGROUND);
    }

    static String episodeCode(Video v) {
        if (v.season == Video.NONE || v.episode == Video.NONE) return v.title;
        return String.format(Locale.ROOT, "S%d E%d", v.season, v.episode);
    }

    // ---- Seasons and episodes ----

    private void showSeason(int season) {
        mSeason = season;
        mSeasonEpisodes = mItem.episodes(season);
        mEpisodes.setAdapter(new EpisodeAdapter());
        RecyclerView.Adapter<?> seasons = mSeasons.getAdapter();
        if (seasons != null) seasons.notifyDataSetChanged();
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
            card.setOnClickListener(c -> playVideo(v, false));
            card.setOnLongClickListener(c -> {
                playVideo(v, true);
                return true;
            });
            card.setOnFocusChangeListener((c, focused) -> {
                float scale = focused ? 1.06f : 1f;
                c.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                if (focused) {
                    mMeta.setText(episodeCode(v) + (v.airDate().isEmpty() ? "" : "  ·  " + v.airDate())
                            + (v.rating > 0 ? String.format(Locale.ROOT, "  ·  ★ %.1f", v.rating) : ""));
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
