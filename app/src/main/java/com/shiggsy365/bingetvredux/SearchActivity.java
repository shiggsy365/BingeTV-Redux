package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.Catalogs;
import com.shiggsy365.bingetvredux.stremio.MetaItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One search across every search catalog (chosen and ordered on the web page), a row of
 * results for each. Searches as you type, after a pause.
 */
public class SearchActivity extends Activity implements CatalogRows.Listener {
    private static final long TYPING_PAUSE_MS = 700;
    private static final int MIN_LENGTH = 2;

    private EditText mQuery;
    private TextView mStatus;
    private CatalogRows mRows;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private String mSearched = "";
    private int mGeneration;
    private final Runnable mSearch = this::search;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_activity);
        mQuery = findViewById(R.id.query);
        mStatus = findViewById(R.id.status);
        mRows = new CatalogRows(this, findViewById(R.id.rows), this);
        if (Stremio.addons(this).searchCatalogs().isEmpty()) {
            mStatus.setText("None of your addons can search. Turn on search catalogs on the "
                    + "web page's Catalogs tab.");
        }
        mQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mHandler.removeCallbacks(mSearch);
                mHandler.postDelayed(mSearch, TYPING_PAUSE_MS);
            }
        });
        mQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                mHandler.removeCallbacks(mSearch);
                search();
                // Down to the results.
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.hideSoftInputFromWindow(mQuery.getWindowToken(), 0);
                findViewById(R.id.rows).requestFocus();
                return true;
            }
            return false;
        });
        mQuery.requestFocus();
    }

    @Override
    protected void onDestroy() {
        mRows.destroy();
        mExecutor.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void search() {
        String query = mQuery.getText().toString().trim();
        if (query.length() < MIN_LENGTH || query.equalsIgnoreCase(mSearched)) return;
        mSearched = query;
        int generation = ++mGeneration;
        List<AddonStore.Entry> entries = Stremio.addons(this).searchCatalogs();
        if (entries.isEmpty()) return;
        mStatus.setText("Searching for “" + query + "”…");
        mExecutor.execute(() -> {
            List<Catalogs.SearchResult> results = Catalogs.search(Stremio.client(this), entries, query);
            runOnUiThread(() -> {
                if (isDestroyed() || generation != mGeneration) return;
                List<CatalogRows.Row> rows = new ArrayList<>();
                int found = 0;
                for (Catalogs.SearchResult r : results) {
                    if (r.items.isEmpty()) continue;
                    found += r.items.size();
                    rows.add(new CatalogRows.Row(title(r.entry), r.items));
                }
                mRows.setRows(rows);
                mStatus.setText(found == 0 ? "Nothing found for “" + query + "”" : "");
            });
        });
    }

    /**
     * The name given on the web page, else what the row holds and where from: "Films ·
     * AIOMetadata", "People in TV shows · AIOMetadata".
     */
    private static String title(AddonStore.Entry e) {
        String custom = e.prefs().searchName;
        if (!custom.isEmpty()) return custom;
        String type = "movie".equals(e.catalog.type) ? "Films"
                : "series".equals(e.catalog.type) ? "TV shows"
                : e.catalog.type.substring(0, 1).toUpperCase(Locale.ROOT) + e.catalog.type.substring(1);
        if (e.catalog.isPeopleSearch()) type = "People in " + type.toLowerCase(Locale.ROOT);
        return type + " · " + e.addon.manifest.name;
    }

    @Override
    public void onItemFocused(MetaItem item) {}

    @Override
    public void onItemClicked(MetaItem item) {
        startActivity(DetailsActivity.intent(this, item));
    }
}
