package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
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
import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.StremioException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rows of posters in a VerticalGridView, as on Home, the hubs and Search. A catalog row loads
 * when it's first shown (from the cache straight away, then from the addon) and loads its next
 * page as focus nears its end. Rows that turn out empty are hidden.
 */
public final class CatalogRows {
    /** What happens when a poster is focused or chosen. */
    public interface Listener {
        void onItemFocused(MetaItem item);

        void onItemClicked(MetaItem item);
    }

    /** One row: a catalog to load, items worked out by code (loader), or a fixed list. */
    public static final class Row {
        final String title;
        final AddonStore.Entry entry;
        final java.util.concurrent.Callable<List<MetaItem>> loader;
        final List<MetaItem> items = new ArrayList<>();
        int nextSkip;
        int pageSize;
        boolean loading;
        boolean loaded;
        boolean failed;
        PosterAdapter adapter;

        public Row(String title, AddonStore.Entry entry) {
            this.title = title;
            this.entry = entry;
            this.loader = null;
        }

        /** Items from {@code loader}, run off the main thread when the row is first shown. */
        public Row(String title, java.util.concurrent.Callable<List<MetaItem>> loader) {
            this.title = title;
            this.entry = null;
            this.loader = loader;
        }

        public Row(String title, List<MetaItem> items) {
            this.title = title;
            this.entry = null;
            this.loader = null;
            this.items.addAll(items);
            nextSkip = -1;
            loaded = true;
        }
    }

    private static final int PAGE_AHEAD = 8;

    private final Activity mActivity;
    private final VerticalGridView mGrid;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final List<Row> mAll = new ArrayList<>();
    // The rows shown: all of them but the empty ones.
    private final List<Row> mShown = new ArrayList<>();
    private final RowsAdapter mAdapter = new RowsAdapter();
    private Runnable mOnChanged;

    public CatalogRows(Activity activity, VerticalGridView grid, Listener listener) {
        mActivity = activity;
        mGrid = grid;
        mListener = listener;
        // The focused row sits at the top of the grid.
        grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE);
        grid.setWindowAlignmentOffset(0);
        grid.setWindowAlignmentOffsetPercent(0f);
        grid.setItemAlignmentOffsetPercent(0f);
        grid.setAdapter(mAdapter);
    }

    /** Called when rows appear or disappear (e.g. to show "nothing here"). */
    public void setOnChanged(Runnable onChanged) {
        mOnChanged = onChanged;
    }

    public void setRows(List<Row> rows) {
        mAll.clear();
        mAll.addAll(rows);
        refreshShown();
        mGrid.scrollToPosition(0);
    }

    public int shownCount() {
        return mShown.size();
    }

    /** Whether every row has finished loading. */
    public boolean allLoaded() {
        for (Row r : mAll) if (!r.loaded && !r.failed) return false;
        return true;
    }

    public void destroy() {
        mExecutor.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void refreshShown() {
        mShown.clear();
        for (Row r : mAll) if (!(r.loaded || r.failed) || !r.items.isEmpty()) mShown.add(r);
        mAdapter.notifyDataSetChanged();
        if (mOnChanged != null) mOnChanged.run();
    }

    // ---- Loading ----

    private void load(Row row) {
        if (row.loader != null) {
            if (row.loading || row.loaded) return;
            row.loading = true;
            mExecutor.execute(() -> {
                List<MetaItem> items;
                try {
                    items = row.loader.call();
                } catch (Exception e) {
                    items = new ArrayList<>();
                }
                List<MetaItem> result = items;
                mHandler.post(() -> showItems(row, result));
            });
            return;
        }
        if (row.entry == null || row.loading || (row.loaded && row.nextSkip < 0)) return;
        boolean first = !row.loaded;
        int skip = first ? 0 : row.nextSkip;
        row.loading = true;
        mExecutor.execute(() -> {
            if (first) {
                Catalogs.Page cached = Catalogs.peek(Stremio.client(mActivity), row.entry.addon,
                        row.entry.catalog, Collections.emptyMap());
                if (cached != null) mHandler.post(() -> showPage(row, cached, true, true));
            }
            try {
                Catalogs.Page page = Catalogs.fetch(Stremio.client(mActivity), row.entry.addon,
                        row.entry.catalog, Collections.emptyMap(), skip, row.pageSize);
                mHandler.post(() -> showPage(row, page, first, false));
            } catch (StremioException e) {
                mHandler.post(() -> {
                    row.loading = false;
                    if (!row.loaded) {
                        row.failed = true;
                        refreshShown();
                    }
                });
            }
        });
    }

    private void showPage(Row row, Catalogs.Page page, boolean replace, boolean fromCache) {
        if (mActivity.isDestroyed()) return;
        if (!fromCache) row.loading = false;
        row.nextSkip = page.nextSkip;
        row.pageSize = page.pageSize;
        // The cached copy is already on screen: only redraw if the addon's answer differs.
        if (replace && !fromCache && row.loaded && sameItems(row.items, page.items)) return;
        int before = replace ? 0 : row.items.size();
        if (replace) row.items.clear();
        row.items.addAll(page.items);
        row.loaded = true;
        if (row.items.isEmpty()) {
            refreshShown();
        } else if (row.adapter != null) {
            if (replace) row.adapter.notifyDataSetChanged();
            // New items go in before the "See all" card.
            else row.adapter.notifyItemRangeInserted(before, row.items.size() - before);
        }
    }

    private void showItems(Row row, List<MetaItem> items) {
        if (mActivity.isDestroyed()) return;
        row.loading = false;
        row.loaded = true;
        row.nextSkip = -1;
        row.items.clear();
        row.items.addAll(items);
        if (items.isEmpty()) refreshShown();
        else if (row.adapter != null) row.adapter.notifyDataSetChanged();
    }

    private static boolean sameItems(List<MetaItem> a, List<MetaItem> b) {
        if (a.size() < b.size()) return false;
        for (int i = 0; i < b.size(); i++) if (!a.get(i).id.equals(b.get(i).id)) return false;
        return true;
    }

    // ---- Adapters ----

    private final class RowsAdapter extends RecyclerView.Adapter<RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.home_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Row row = mShown.get(position);
            holder.title.setText(row.title);
            if (row.adapter == null) row.adapter = new PosterAdapter(row);
            holder.items.setAdapter(row.adapter);
            if (!row.loaded) load(row);
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final HorizontalGridView items;

        RowHolder(View view) {
            super(view);
            title = view.findViewById(R.id.row_title);
            items = view.findViewById(R.id.row_items);
            // Focus stays at the left of the row as it scrolls, like the title above it.
            int margin = view.getResources().getDimensionPixelSize(R.dimen.screen_margin);
            items.setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE);
            items.setWindowAlignmentOffset(margin);
            items.setWindowAlignmentOffsetPercent(0f);
            items.setItemAlignmentOffsetPercent(0f);
            int height = view.getResources().getDimensionPixelSize(R.dimen.poster_height);
            items.setRowHeight(height);
        }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<PosterHolder> {
        private static final int TYPE_POSTER = 0, TYPE_SEE_ALL = 1;
        private final Row mRow;

        PosterAdapter(Row row) {
            mRow = row;
        }

        /** A catalog row ends with a "See all" card that opens the whole catalog. */
        private boolean hasSeeAll() {
            return mRow.entry != null && !mRow.items.isEmpty();
        }

        @Override
        public int getItemViewType(int position) {
            return position < mRow.items.size() ? TYPE_POSTER : TYPE_SEE_ALL;
        }

        @NonNull
        @Override
        public PosterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PosterHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.poster_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PosterHolder holder, int position) {
            if (getItemViewType(position) == TYPE_SEE_ALL) {
                ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                lp.width = holder.itemView.getResources().getDimensionPixelSize(R.dimen.poster_width);
                lp.height = holder.itemView.getResources().getDimensionPixelSize(R.dimen.poster_height);
                holder.itemView.setLayoutParams(lp);
                holder.name.setText("See all\n›");
                Images.load(holder.image, null);
                Badges.clear(holder.itemView);
                holder.itemView.setOnClickListener(v -> mActivity.startActivity(
                        CatalogActivity.intent(mActivity, mRow.entry, mRow.title)));
                holder.itemView.setOnFocusChangeListener((v, focused) -> {
                    float scale = focused ? 1.08f : 1f;
                    v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                });
                return;
            }
            MetaItem item = mRow.items.get(position);
            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            boolean landscape = "landscape".equals(item.posterShape);
            lp.width = holder.itemView.getResources().getDimensionPixelSize(
                    landscape ? R.dimen.landscape_width : R.dimen.poster_width);
            lp.height = holder.itemView.getResources().getDimensionPixelSize(
                    landscape ? R.dimen.landscape_height : R.dimen.poster_height);
            holder.itemView.setLayoutParams(lp);
            holder.name.setText(item.name);
            // Channel logos are shown whole; artwork fills the card.
            holder.image.setScaleType(item.id.startsWith(HomeRows.LIVE_PREFIX)
                    ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            Images.load(holder.image, item.poster);
            Badges.bind(holder.itemView, item);
            holder.itemView.setOnClickListener(v -> mListener.onItemClicked(item));
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.08f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                v.setElevation(focused ? 8f : 0f);
                if (focused) {
                    mListener.onItemFocused(item);
                    if (mRow.items.size() - position <= PAGE_AHEAD) load(mRow);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mRow.items.size() + (hasSeeAll() ? 1 : 0);
        }
    }

    private static final class PosterHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView name;

        PosterHolder(View view) {
            super(view);
            image = view.findViewById(R.id.card_image);
            name = view.findViewById(R.id.card_name);
            view.setClipToOutline(true);
        }
    }
}
