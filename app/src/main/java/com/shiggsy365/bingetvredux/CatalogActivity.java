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
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.Catalogs;
import com.shiggsy365.bingetvredux.stremio.Manifest;
import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.StremioException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A whole catalog as a grid of posters, loading more as focus nears the end. A catalog with a
 * filter (usually genre) shows its options along the top; a required one has no "All".
 */
public class CatalogActivity extends Activity {
    private static final String EXTRA_KEY = "key";
    private static final String EXTRA_TITLE = "title";
    private static final int COLUMNS = 7;

    private TextView mStatus;
    private VerticalGridView mGrid;
    private HorizontalGridView mFilters;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private AddonStore.Entry mEntry;
    private Manifest.Extra mFilter;
    private List<String> mOptions = new ArrayList<>();
    private String mOption = "";
    private final List<MetaItem> mItems = new ArrayList<>();
    private final PosterAdapter mAdapter = new PosterAdapter();
    private int mNextSkip;
    private int mPageSize;
    private boolean mLoading;
    // Which load is current, so a page for a filter no longer chosen is dropped.
    private int mGeneration;

    public static Intent intent(Context context, AddonStore.Entry entry, String title) {
        return new Intent(context, CatalogActivity.class)
                .putExtra(EXTRA_KEY, entry.key()).putExtra(EXTRA_TITLE, title);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.catalog_activity);
        mStatus = findViewById(R.id.status);
        mGrid = findViewById(R.id.grid);
        mFilters = findViewById(R.id.filters);
        ((TextView) findViewById(R.id.title)).setText(getIntent().getStringExtra(EXTRA_TITLE));
        try {
            mEntry = Stremio.addons(this).entry(getIntent().getStringExtra(EXTRA_KEY));
        } catch (StremioException e) {
            finish();
            return;
        }
        mGrid.setNumColumns(COLUMNS);
        mGrid.setVerticalSpacing(getResources().getDimensionPixelSize(R.dimen.grid_spacing));
        mGrid.setAdapter(mAdapter);

        List<Manifest.Extra> filters = mEntry.catalog.filters();
        if (!filters.isEmpty()) {
            mFilter = filters.get(0);
            if (!mFilter.isRequired) mOptions.add("");
            mOptions.addAll(mFilter.options);
            mOption = mOptions.get(0);
            mFilters.setVisibility(View.VISIBLE);
            int margin = getResources().getDimensionPixelSize(R.dimen.screen_margin);
            mFilters.setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE);
            mFilters.setWindowAlignmentOffset(margin);
            mFilters.setWindowAlignmentOffsetPercent(0f);
            mFilters.setItemAlignmentOffsetPercent(0f);
            mFilters.setAdapter(new FilterAdapter());
        }
        reload();
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        mGeneration++;
        mItems.clear();
        mAdapter.notifyDataSetChanged();
        mNextSkip = 0;
        mPageSize = 0;
        mLoading = false;
        mStatus.setText("Loading…");
        mStatus.setVisibility(View.VISIBLE);
        loadMore();
    }

    private void loadMore() {
        if (mLoading || mNextSkip < 0) return;
        mLoading = true;
        int generation = mGeneration;
        int skip = mNextSkip;
        int pageSize = mPageSize;
        Map<String, String> filters = new LinkedHashMap<>();
        if (mFilter != null && !mOption.isEmpty()) filters.put(mFilter.name, mOption);
        mExecutor.execute(() -> {
            Catalogs.Page page = null;
            String error = null;
            try {
                page = Catalogs.fetch(Stremio.client(this), mEntry.addon, mEntry.catalog, filters,
                        skip, pageSize);
            } catch (StremioException e) {
                error = e.getMessage();
            }
            Catalogs.Page result = page;
            String message = error;
            runOnUiThread(() -> {
                if (isDestroyed() || generation != mGeneration) return;
                mLoading = false;
                if (result == null) {
                    mNextSkip = -1;
                    if (mItems.isEmpty()) mStatus.setText(message);
                    return;
                }
                int before = mItems.size();
                mItems.addAll(result.items);
                mNextSkip = result.nextSkip;
                mPageSize = result.pageSize;
                mAdapter.notifyItemRangeInserted(before, result.items.size());
                mStatus.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
                if (mItems.isEmpty()) mStatus.setText("Nothing here");
                if (before == 0 && !mItems.isEmpty() && !mFilters.hasFocus()) mGrid.requestFocus();
            });
        });
    }

    private final class FilterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.season_chip, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            String option = mOptions.get(position);
            TextView chip = (TextView) holder.itemView;
            chip.setText(option.isEmpty() ? "All" : option);
            chip.setSelected(option.equals(mOption));
            chip.setOnClickListener(v -> {
                if (option.equals(mOption)) return;
                mOption = option;
                notifyDataSetChanged();
                reload();
            });
        }

        @Override
        public int getItemCount() {
            return mOptions.size();
        }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.poster_card, parent, false);
            view.setClipToOutline(true);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MetaItem item = mItems.get(position);
            View view = holder.itemView;
            ((TextView) view.findViewById(R.id.card_name)).setText(item.name);
            Images.load((ImageView) view.findViewById(R.id.card_image), item.poster);
            Badges.bind(view, item);
            view.setOnClickListener(v -> startActivity(DetailsActivity.intent(
                    CatalogActivity.this, item)));
            view.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.08f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                v.setElevation(focused ? 8f : 0f);
                if (focused && mItems.size() - position <= COLUMNS * 3) loadMore();
            });
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }
}
