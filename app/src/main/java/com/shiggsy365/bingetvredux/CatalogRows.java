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
    // Blank cards in a row that's still loading: enough to cross the screen.
    private static final int PLACEHOLDERS = 8;
    // A catalog row shows this many at most; "See all" opens the rest.
    private static final int ROW_MAX = 50;

    private final Activity mActivity;
    private final VerticalGridView mGrid;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Background.Tasks mExecutor = new Background.Tasks(4);
    private final List<Row> mAll = new ArrayList<>();
    // The rows shown: all of them but the empty ones.
    private final List<Row> mShown = new ArrayList<>();
    private final RowsAdapter mAdapter = new RowsAdapter();
    private Runnable mOnChanged;
    // The focused poster's item and row, so a row reloaded under it can update the hero.
    private MetaItem mFocused;
    private Row mFocusedRow;
    private final java.util.Random mRandom = new java.util.Random();
    // A started show's episodes became known: its poster may now get the watched ribbon.
    private final Runnable mRedrawBadges = () -> {
        mHandler.removeCallbacks(this.mRedrawRows);
        mHandler.postDelayed(this.mRedrawRows, 300);
    };
    private final Runnable mRedrawRows = () -> {
        for (Row r : mAll) if (r.adapter != null) r.adapter.notifyDataSetChanged();
    };

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
        ShowProgress.addListener(mRedrawBadges);
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

    /** A random item of the first row that has any; null if none has loaded yet. */
    public MetaItem randomFirstItem() {
        for (Row r : mShown) {
            if (!r.items.isEmpty()) return r.items.get(mRandom.nextInt(r.items.size()));
        }
        return null;
    }

    /**
     * Loads the named rows' items again (rows worked out by code, such as Continue Watching),
     * keeping the rest of the page and the focus as they are. Each keeps showing what it had
     * until its new items come.
     */
    public void reload(java.util.Collection<String> titles) {
        for (Row r : mAll) {
            if (r.loader == null || r.loading || !r.loaded || !titles.contains(r.title)) continue;
            runLoader(r);
        }
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
        ShowProgress.removeListener(mRedrawBadges);
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
            runLoader(row);
            return;
        }
        if (row.entry == null || row.loading
                || (row.loaded && (row.nextSkip < 0 || row.items.size() >= ROW_MAX))) {
            return;
        }
        boolean first = !row.loaded;
        int skip = first ? 0 : row.nextSkip;
        row.loading = true;
        mExecutor.execute(() -> {
            if (first) {
                Catalogs.Page cached = Catalogs.peek(Stremio.client(mActivity), row.entry.addon,
                        row.entry.catalog, Collections.emptyMap());
                if (cached != null) {
                    Catalogs.Page shown = cached.withItems(
                            PreferredMetas.fromCache(mActivity, row.entry.addon, cached.items));
                    mHandler.post(() -> showPage(row, shown, true, true));
                }
            }
            try {
                Catalogs.Page page = Catalogs.fetch(Stremio.client(mActivity), row.entry.addon,
                        row.entry.catalog, Collections.emptyMap(), skip, row.pageSize);
                Catalogs.Page shown = page.withItems(
                        PreferredMetas.fromCache(mActivity, row.entry.addon, page.items));
                mHandler.post(() -> showPage(row, shown, first, false));
                // Then the first metadata addon's details for items not in the cache yet.
                if (PreferredMetas.needed(mActivity, row.entry.addon, page.items)) {
                    List<MetaItem> preferred = PreferredMetas.fetch(mActivity, row.entry.addon, page.items);
                    mHandler.post(() -> replaceItems(row, preferred));
                }
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

    private void runLoader(Row row) {
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
        row.items.addAll(page.items.subList(0, Math.min(page.items.size(),
                Math.max(0, ROW_MAX - row.items.size()))));
        row.loaded = true;
        if (row.items.isEmpty()) {
            refreshShown();
        } else if (row.adapter != null) {
            if (replace) row.adapter.notifyDataSetChanged();
            // New items go in before the "See all" card.
            else row.adapter.notifyItemRangeInserted(before, row.items.size() - before);
        }
    }

    /** Swaps in newer details for items already in the row (matched by id). */
    private void replaceItems(Row row, List<MetaItem> items) {
        if (mActivity.isDestroyed()) return;
        java.util.Map<String, MetaItem> byId = new java.util.HashMap<>();
        for (MetaItem m : items) byId.put(m.id, m);
        boolean changed = false;
        for (int i = 0; i < row.items.size(); i++) {
            MetaItem newer = byId.get(row.items.get(i).id);
            if (newer != null && newer != row.items.get(i)) {
                row.items.set(i, newer);
                changed = true;
            }
        }
        if (changed && row.adapter != null) row.adapter.notifyDataSetChanged();
    }

    private void showItems(Row row, List<MetaItem> items) {
        if (mActivity.isDestroyed()) return;
        row.loading = false;
        row.loaded = true;
        row.nextSkip = -1;
        boolean same = sameItems(row.items, items) && row.items.size() == items.size();
        row.items.clear();
        row.items.addAll(items);
        // A reloaded row may have emptied, or a hidden one gained items.
        if (items.isEmpty() || !mShown.contains(row)) refreshShown();
        else if (row.adapter != null) {
            if (same) row.adapter.notifyItemRangeChanged(0, items.size());
            else row.adapter.notifyDataSetChanged();
        }
        // The focused title's details, if it's still in the row, are now these.
        if (mFocusedRow == row && mFocused != null) {
            for (MetaItem m : items) {
                if (!m.id.equals(mFocused.id)) continue;
                boolean changed = !m.subtitle.equals(mFocused.subtitle)
                        || !m.description.equals(mFocused.description)
                        || !m.background.equals(mFocused.background);
                mFocused = m;
                if (changed) mListener.onItemFocused(m);
                break;
            }
        }
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
        private static final int TYPE_POSTER = 0, TYPE_SEE_ALL = 1, TYPE_PLACEHOLDER = 2;
        private final Row mRow;

        PosterAdapter(Row row) {
            mRow = row;
        }

        /** A catalog row ends with a "See all" card that opens the whole catalog. */
        private boolean hasSeeAll() {
            return mRow.entry != null && !mRow.items.isEmpty();
        }

        /** Still loading with nothing yet: blank cards hold the row's place. */
        private boolean placeholders() {
            return mRow.items.isEmpty() && !mRow.loaded && !mRow.failed;
        }

        @Override
        public int getItemViewType(int position) {
            if (placeholders()) return TYPE_PLACEHOLDER;
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
            if (getItemViewType(position) == TYPE_PLACEHOLDER) {
                ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                lp.width = holder.itemView.getResources().getDimensionPixelSize(R.dimen.poster_width);
                lp.height = holder.itemView.getResources().getDimensionPixelSize(R.dimen.poster_height);
                holder.itemView.setLayoutParams(lp);
                holder.name.setText(null);
                Images.load(holder.image, null);
                Badges.clear(holder.itemView);
                holder.itemView.setFocusable(false);
                holder.itemView.setAlpha(0.5f);
                holder.itemView.setOnClickListener(null);
                holder.itemView.setOnFocusChangeListener(null);
                return;
            }
            holder.itemView.setFocusable(true);
            holder.itemView.setAlpha(1f);
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
                    CardFocus.apply(v, focused, CardFocus.POSTER);
                });
                return;
            }
            MetaItem item = mRow.items.get(position);
            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            boolean landscape = "landscape".equals(item.posterShape);
            boolean square = "square".equals(item.posterShape);
            boolean live = item.id.startsWith(HomeRows.LIVE_PREFIX);
            lp.width = holder.itemView.getResources().getDimensionPixelSize(square ? R.dimen.square_size
                    : landscape ? R.dimen.landscape_width : R.dimen.poster_width);
            lp.height = holder.itemView.getResources().getDimensionPixelSize(square ? R.dimen.square_size
                    : landscape ? R.dimen.landscape_height : R.dimen.poster_height);
            holder.itemView.setLayoutParams(lp);
            // A channel's name only when it has no logo (it would show through a logo's
            // transparent parts); other titles keep theirs behind the artwork, for when it fails.
            holder.name.setText(live && !item.poster.isEmpty() ? null : item.name);
            // Channel logos are shown whole, with a margin; artwork fills the card.
            holder.image.setScaleType(live ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            int pad = live ? Math.round(12 * holder.itemView.getResources().getDisplayMetrics().density) : 0;
            holder.image.setPadding(pad, pad, pad, pad);
            Images.load(holder.image, item.poster);
            Badges.bind(holder.itemView, item);
            holder.itemView.setOnClickListener(v -> mListener.onItemClicked(item));
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                CardFocus.apply(v, focused, CardFocus.POSTER);
                if (focused) {
                    mFocused = item;
                    mFocusedRow = mRow;
                    mListener.onItemFocused(item);
                    if (mRow.items.size() - position <= PAGE_AHEAD) load(mRow);
                }
            });
        }

        @Override
        public int getItemCount() {
            if (placeholders()) return PLACEHOLDERS;
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
