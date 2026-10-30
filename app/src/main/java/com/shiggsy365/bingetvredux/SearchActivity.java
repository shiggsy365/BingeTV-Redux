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
 *
 * Under the box: the profile's recent searches while it's empty, and IMDb's suggestions
 * while typing (a title opens its info page, a person searches for them).
 */
public class SearchActivity extends Activity implements CatalogRows.Listener {
    private static final String EXTRA_PERSON = "person";
    private static final long TYPING_PAUSE_MS = 700;
    private static final int MIN_LENGTH = 2;
    private static final int REQUEST_VOICE = 1;
    private static final int RECENT_MAX = 12;
    private static final int SUGGESTIONS_MAX = 8;
    private static final long SUGGEST_PAUSE_MS = 250;
    private static final String SUGGEST_URL = "https://v3.sg.media-imdb.com/suggestion/x/";

    private EditText mQuery;
    private TextView mStatus;
    private CatalogRows mRows;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Background.Tasks mExecutor = new Background.Tasks(1);
    private String mSearched = "";
    private int mGeneration;
    private final Runnable mSearch = this::search;
    private final Runnable mSuggest = this::suggest;
    private final Background.Tasks mSuggestions = new Background.Tasks(1);
    private android.view.ViewGroup mChips;
    private View mChipsScroll;
    private int mSuggestGeneration;
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
        mChips = findViewById(R.id.chips);
        mChipsScroll = findViewById(R.id.chips_scroll);
        ((android.view.ViewGroup) findViewById(R.id.rows)).setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.search_rows_in));
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
                mHandler.removeCallbacks(mSuggest);
                mHandler.postDelayed(mSuggest, SUGGEST_PAUSE_MS);
            }
        });
        mQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                mHandler.removeCallbacks(mSearch);
                search();
                remember(mQuery.getText().toString());
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
            showRecent();
        }
    }

    // ---- Recent searches and suggestions

    /** The profile's searches, most recent first. */
    private List<String> recent() {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray list = new org.json.JSONArray(Settings.data(this, Settings.RECENT_SEARCHES));
            for (int i = 0; i < list.length(); i++) out.add(list.getString(i));
        } catch (org.json.JSONException e) {
            // None yet.
        }
        return out;
    }

    /** Remembers a search that was meant (opened a result, pressed Search, or was picked). */
    private void remember(String query) {
        query = query.trim();
        if (query.length() < MIN_LENGTH || mPerson) return;
        List<String> list = recent();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equalsIgnoreCase(query)) list.remove(i);
        }
        list.add(0, query);
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < Math.min(RECENT_MAX, list.size()); i++) out.put(list.get(i));
        Settings.putData(this, Settings.RECENT_SEARCHES, out.toString());
    }

    private void showRecent() {
        List<String> list = recent();
        mChips.removeAllViews();
        if (list.isEmpty()) {
            mChipsScroll.setVisibility(View.GONE);
            return;
        }
        addChipLabel("Recent");
        for (String q : list) addChip(q, () -> runSearch(q));
        addChip("Clear", () -> {
            Settings.putData(this, Settings.RECENT_SEARCHES, "");
            showRecent();
            mQuery.requestFocus();
        });
        mChipsScroll.setVisibility(View.VISIBLE);
    }

    /** IMDb's suggestions for what's typed so far; recent searches when the box is empty. */
    private void suggest() {
        String query = mQuery.getText().toString().trim();
        int generation = ++mSuggestGeneration;
        if (query.isEmpty()) {
            showRecent();
            return;
        }
        if (query.length() < MIN_LENGTH || mPerson) return;
        mSuggestions.execute(() -> {
            List<String[]> found = new ArrayList<>();
            try {
                String key = query.toLowerCase(Locale.ROOT);
                com.shiggsy365.bingetvredux.stremio.Http.Response r = com.shiggsy365.bingetvredux.stremio.Http
                        .get(SUGGEST_URL + android.net.Uri.encode(key) + ".json").timeout(5_000).send();
                if (r.ok()) {
                    org.json.JSONArray d = new org.json.JSONObject(r.text()).optJSONArray("d");
                    for (int i = 0; d != null && i < d.length() && found.size() < SUGGESTIONS_MAX; i++) {
                        org.json.JSONObject s = d.getJSONObject(i);
                        String id = s.optString("id"), name = s.optString("l"), kind = s.optString("qid");
                        if (name.isEmpty()) continue;
                        String type = kind.equals("movie") || kind.equals("tvMovie") ? "movie"
                                : kind.startsWith("tvSeries") || kind.equals("tvMiniSeries") ? "series"
                                : id.startsWith("nm") ? "person" : null;
                        if (type == null) continue;
                        int year = s.optInt("y", 0);
                        found.add(new String[] {type, id, name, year > 0 ? String.valueOf(year) : ""});
                    }
                }
            } catch (java.io.IOException | org.json.JSONException e) {
                // No suggestions this time; the search itself still runs.
            }
            runOnUiThread(() -> {
                if (isDestroyed() || generation != mSuggestGeneration) return;
                showSuggestions(found);
            });
        });
    }

    private void showSuggestions(List<String[]> found) {
        mChips.removeAllViews();
        if (found.isEmpty()) {
            mChipsScroll.setVisibility(View.GONE);
            return;
        }
        addChipLabel("Suggestions");
        for (String[] s : found) {
            String type = s[0], id = s[1], name = s[2], year = s[3];
            if (type.equals("person")) {
                addChip("👤 " + name, () -> {
                    remember(name);
                    startActivity(personIntent(this, name));
                });
            } else {
                addChip(year.isEmpty() ? name : name + "  (" + year + ")", () -> {
                    remember(mQuery.getText().toString());
                    startActivity(new Intent(this, DetailsActivity.class)
                            .putExtra("type", type).putExtra("id", id).putExtra("name", name));
                });
            }
        }
        mChipsScroll.setVisibility(View.VISIBLE);
    }

    private void addChipLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.guide_muted));
        label.setTextSize(14);
        label.setPadding(0, 0, Math.round(12 * getResources().getDisplayMetrics().density), 0);
        mChips.addView(label);
    }

    private void addChip(String text, Runnable onClick) {
        TextView chip = new TextView(new android.view.ContextThemeWrapper(this, R.style.MenuItem), null, 0);
        chip.setText(text);
        chip.setSingleLine(true);
        chip.setOnClickListener(v -> onClick.run());
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(8 * getResources().getDisplayMetrics().density));
        mChips.addView(chip, lp);
    }

    /** A recent search picked: searched straight away, then down to the results. */
    private void runSearch(String query) {
        mQuery.setText(query);
        mQuery.setSelection(query.length());
        mHandler.removeCallbacks(mSearch);
        mHandler.removeCallbacks(mSuggest);
        search();
        remember(query);
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
        mSuggestions.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void search() {
        String query = mQuery.getText().toString().trim();
        if (query.length() < MIN_LENGTH || query.equalsIgnoreCase(mSearched)) return;
        mSearched = query;
        int generation = ++mGeneration;
        List<AddonStore.Entry> entries = searchEntries();
        // With no search catalogs, podcasts and radio can still be searched.
        if (entries.isEmpty() && !Settings.bool(this, Settings.PODCASTS_PAGE)
                && !Settings.bool(this, Settings.RADIO)) {
            return;
        }
        mStatus.setText("Searching for “" + query + "”…");
        mExecutor.execute(() -> {
            List<Catalogs.SearchResult> raw = Catalogs.search(Stremio.client(this), entries, query);
            // Podcasts and radio stations too, unless the profile has them off (not for people).
            List<MetaItem> podcasts = new ArrayList<>(), stations = new ArrayList<>();
            if (!mPerson && Settings.bool(this, Settings.PODCASTS_PAGE)) {
                try {
                    podcasts = Podcasts.podcastMetas(Podcasts.search(this, query));
                } catch (java.io.IOException e) {
                    // Films and shows still show.
                }
            }
            if (!mPerson && Settings.bool(this, Settings.RADIO)) {
                try {
                    stations = Radio.toMetas(Radio.search(query));
                } catch (java.io.IOException e) {
                    // As above.
                }
            }
            List<MetaItem> foundPodcasts = podcasts, foundStations = stations;
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
                if (!foundPodcasts.isEmpty()) rows.add(new CatalogRows.Row("Podcasts", foundPodcasts));
                if (!foundStations.isEmpty()) rows.add(new CatalogRows.Row("Radio stations", foundStations));
                found += foundPodcasts.size() + foundStations.size();
                mRows.setRows(rows);
                // The rows arrive one after another.
                ((android.view.ViewGroup) findViewById(R.id.rows)).scheduleLayoutAnimation();
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
        remember(mSearched);
        if (AudioRows.open(this, item)) return;
        startActivity(DetailsActivity.intent(this, item));
    }
}
