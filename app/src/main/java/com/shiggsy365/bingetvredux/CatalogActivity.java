package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A whole catalog as a grid of posters, loading more as focus nears the end. The focused title's
 * logo (or name) and description are at the top left. A catalog with a filter (usually genre)
 * has it as a drop-down at the top right; a required one has no "All".
 */
public class CatalogActivity extends Activity {
    private static final String EXTRA_KEY = "key";
    private static final String EXTRA_TITLE = "title";
    private static final int COLUMNS = 7;

    private TextView mStatus;
    private VerticalGridView mGrid;
    private TextView mFilterButton;
    private ImageView mFocusedLogo;
    private TextView mFocusedTitle;
    private TextView mFocusedDesc;
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

    // A started show's episodes became known: its poster may now get the watched ribbon.
    private final Runnable mRedrawBadges = () -> mAdapter.notifyDataSetChanged();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.catalog_activity);
        mStatus = findViewById(R.id.status);
        mGrid = findViewById(R.id.grid);
        mFilterButton = findViewById(R.id.filter);
        mFocusedLogo = findViewById(R.id.focused_logo);
        mFocusedTitle = findViewById(R.id.focused_title);
        mFocusedDesc = findViewById(R.id.focused_desc);
        // Until a title has focus, the top shows the catalog's name.
        mFocusedTitle.setText(getIntent().getStringExtra(EXTRA_TITLE));
        try {
            mEntry = Stremio.addons(this).entry(getIntent().getStringExtra(EXTRA_KEY));
        } catch (StremioException e) {
            finish();
            return;
        }
        mGrid.setNumColumns(COLUMNS);
        mGrid.setVerticalSpacing(getResources().getDimensionPixelSize(R.dimen.grid_spacing));
        mGrid.setAdapter(mAdapter);
        // Up from the first row goes to the filter, at the far right of the top (the grid
        // keeps Up to itself otherwise).
        mGrid.setOnKeyInterceptListener(event -> {
            if (event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP || mFilter == null
                    || mGrid.getSelectedPosition() >= COLUMNS) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) mFilterButton.requestFocus();
            return true;
        });
        ShowProgress.addListener(mRedrawBadges);

        List<Manifest.Extra> filters = mEntry.catalog.filters();
        if (!filters.isEmpty()) {
            mFilter = filters.get(0);
            if (!mFilter.isRequired) mOptions.add("");
            mOptions.addAll(mFilter.options);
            mOption = mOptions.get(0);
            mFilterButton.setVisibility(View.VISIBLE);
            mFilterButton.setOnClickListener(v -> chooseFilter());
            showFilter();
        }
        reload();
    }

    @Override
    protected void onDestroy() {
        ShowProgress.removeListener(mRedrawBadges);
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
                page = page.withItems(PreferredMetas.fromCache(this, mEntry.addon, page.items));
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
                // The first page takes the focus (the filter button has it until there's a grid).
                if (before == 0 && !mItems.isEmpty()) mGrid.requestFocus();
            });
            // Then the first metadata addon's details for items not in the cache yet.
            if (result != null && PreferredMetas.needed(this, mEntry.addon, result.items)) {
                List<MetaItem> preferred = PreferredMetas.fetch(this, mEntry.addon, result.items);
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != mGeneration) return;
                    java.util.Map<String, MetaItem> byId = new java.util.HashMap<>();
                    for (MetaItem m : preferred) byId.put(m.id, m);
                    for (int i = 0; i < mItems.size(); i++) {
                        MetaItem newer = byId.get(mItems.get(i).id);
                        if (newer != null) mItems.set(i, newer);
                    }
                    mAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void showFilter() {
        String name = mFilter.name.isEmpty() ? "Filter"
                : mFilter.name.substring(0, 1).toUpperCase(Locale.ROOT) + mFilter.name.substring(1);
        mFilterButton.setText(name + ": " + (mOption.isEmpty() ? "All" : mOption) + "  ▾");
    }

    /** The filter's options in a drop-down under it; choosing one reloads the grid. */
    private void chooseFilter() {
        List<String> labels = new ArrayList<>();
        for (String o : mOptions) labels.add(o.isEmpty() ? "All" : o);
        ListPopupWindow popup = new ListPopupWindow(this);
        int chosen = mOptions.indexOf(mOption);
        popup.setAdapter(new ArrayAdapter<String>(this, R.layout.filter_option, labels) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                view.setActivated(position == chosen);
                return view;
            }
        });
        popup.setAnchorView(mFilterButton);
        popup.setDropDownGravity(Gravity.END);
        float density = getResources().getDisplayMetrics().density;
        popup.setWidth(Math.round(240 * density));
        popup.setHeight(Math.round(Math.min(360, labels.size() * 34 + 8) * density));
        popup.setVerticalOffset(Math.round(6 * density));
        popup.setBackgroundDrawable(getDrawable(R.drawable.filter_popup));
        popup.setModal(true);
        popup.setOnItemClickListener((parent, view, which, id) -> {
            popup.dismiss();
            String option = mOptions.get(which);
            if (option.equals(mOption)) return;
            mOption = option;
            showFilter();
            reload();
        });
        popup.show();
        if (popup.getListView() != null) {
            // The options draw their own highlight.
            popup.getListView().setSelector(android.R.color.transparent);
            popup.getListView().setSelection(Math.max(0, chosen));
            popup.getListView().requestFocus();
        }
    }

    /** The top left follows the focused title: its logo, else its name, and its description. */
    private void showFocused(MetaItem item) {
        boolean hasLogo = !item.logo.isEmpty();
        mFocusedTitle.setText(item.name);
        mFocusedTitle.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
        mFocusedLogo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
        Images.fade(mFocusedLogo, item.logo);
        mFocusedDesc.setText(item.description);
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
                if (focused) showFocused(item);
                if (focused && mItems.size() - position <= COLUMNS * 3) loadMore();
            });
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }
}
