package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
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

/**
 * One search across every search catalog (chosen and ordered on the web page), a row of
 * results for each. Searches as you type, after a pause. Opened for a person (from an info
 * page's cast), it searches their name with the people-search catalogs if there are any.
 */
public class SearchActivity extends Activity implements CatalogRows.Listener {
    private static final String EXTRA_PERSON = "person";
    private static final long TYPING_PAUSE_MS = 700;
    private static final int MIN_LENGTH = 2;
    private static final int REQUEST_VOICE = 1;

    private EditText mQuery;
    private TextView mStatus;
    private CatalogRows mRows;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Background.Tasks mExecutor = new Background.Tasks(1);
    private String mSearched = "";
    private int mGeneration;
    private final Runnable mSearch = this::search;
    private boolean mPerson;

    /** Search for someone's films and shows. */
    public static Intent personIntent(Context context, String name) {
        return new Intent(context, SearchActivity.class).putExtra(EXTRA_PERSON, name);
    }

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
                if (mPerson && mQuery.hasFocus()) mPerson = false;
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
        // Only where apps can have speech: on Fire TV the mic belongs to Alexa.
        View voice = findViewById(R.id.voice);
        voice.setVisibility(canSpeak() ? View.VISIBLE : View.GONE);
        voice.setOnClickListener(v -> voice());
        String person = getIntent().getStringExtra(EXTRA_PERSON);
        if (person != null && !person.isEmpty()) {
            mPerson = true;
            mQuery.setText(person);
            mHandler.removeCallbacks(mSearch);
            search();
            findViewById(R.id.rows).requestFocus();
        } else {
            mQuery.requestFocus();
        }
    }

    private Intent speechIntent() {
        return new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search films, shows and people");
    }

    /** Whether the device has a speech recogniser for apps (Android TV, Google TV; not Fire TV). */
    private boolean canSpeak() {
        return speechIntent().resolveActivity(getPackageManager()) != null;
    }

    /** Speak the search, with the device's recogniser. */
    private void voice() {
        try {
            startActivityForResult(speechIntent(), REQUEST_VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            findViewById(R.id.voice).setVisibility(View.GONE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VOICE || resultCode != RESULT_OK || data == null) return;
        java.util.ArrayList<String> heard =
                data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        mQuery.setText(heard.get(0));
        mHandler.removeCallbacks(mSearch);
        search();
        findViewById(R.id.rows).requestFocus();
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
        List<AddonStore.Entry> entries = searchEntries();
        if (entries.isEmpty()) return;
        mStatus.setText("Searching for “" + query + "”…");
        mExecutor.execute(() -> {
            List<Catalogs.SearchResult> raw = Catalogs.search(Stremio.client(this), entries, query);
            // Each result with the first metadata addon's details (a Cinemeta search shows
            // AIOMetadata's posters and text, if it's above Cinemeta).
            List<Catalogs.SearchResult> results = new ArrayList<>();
            for (Catalogs.SearchResult r : raw) {
                results.add(r.items.isEmpty() || !PreferredMetas.needed(this, r.entry.addon, r.items) ? r
                        : r.withItems(PreferredMetas.fetch(this, r.entry.addon, r.items)));
            }
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

    /** The search catalogs; for a person, only the people-search ones if there are any. */
    private List<AddonStore.Entry> searchEntries() {
        List<AddonStore.Entry> entries = Stremio.addons(this).searchCatalogs();
        if (!mPerson) return entries;
        List<AddonStore.Entry> people = new ArrayList<>();
        for (AddonStore.Entry e : entries) if (e.catalog.isPeopleSearch()) people.add(e);
        return people.isEmpty() ? entries : people;
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
