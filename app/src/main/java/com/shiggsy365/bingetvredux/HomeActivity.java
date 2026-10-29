package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.leanback.widget.VerticalGridView;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.MetaItem;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The app's front page. A menu bar along the top (Home, a page per hub with catalogs, Live TV
 * and Settings), the focused title's details over its backdrop, and rows of posters below.
 */
public class HomeActivity extends Activity implements CatalogRows.Listener {
    static final String PAGE_HOME = "home";
    private static final String PAGE_LIVE = "live";
    private static final String PAGE_SETTINGS = "settings";
    private static final long HERO_DELAY_MS = 150;
    private static final long CLOCK_TICK_MS = 15_000;

    private LinearLayout mMenu;
    private TextView mClock;
    private ImageView mBackdrop;
    private ImageView mHeroLogo;
    private TextView mHeroTitle;
    private TextView mHeroMeta;
    private TextView mHeroDesc;
    private TextView mEmpty;
    private VerticalGridView mRowsView;
    private CatalogRows mRows;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DateFormat mTimeFormat;
    private String mPage = PAGE_HOME;
    // The catalogs on the page, to tell when the web page has changed them.
    private List<String> mPageKeys = new ArrayList<>();
    private MetaItem mHeroItem;
    private MetaItem mPendingHero;
    private final Runnable mShowHero = () -> bindHero(mPendingHero);
    private final Runnable mClockTick = new Runnable() {
        @Override
        public void run() {
            mClock.setText(mTimeFormat.format(new Date()));
            mHandler.postDelayed(this, CLOCK_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.home_activity);
        WebServerService.start(this);
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);
        mMenu = findViewById(R.id.menu_items);
        mClock = findViewById(R.id.clock);
        mBackdrop = findViewById(R.id.backdrop);
        mHeroLogo = findViewById(R.id.hero_logo);
        mHeroTitle = findViewById(R.id.hero_title);
        mHeroMeta = findViewById(R.id.hero_meta);
        mHeroDesc = findViewById(R.id.hero_desc);
        mEmpty = findViewById(R.id.empty);
        mRowsView = findViewById(R.id.rows);
        mRows = new CatalogRows(this, mRowsView, this);
        mRows.setOnChanged(this::updateEmpty);
        if (savedInstanceState != null) mPage = savedInstanceState.getString("page", PAGE_HOME);
        openDebugTitle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openDebugTitle(intent);
    }

    /**
     * Debug builds: {@code am start -n …/.HomeActivity --es debug_type movie --es debug_id tt…}
     * opens that title's info page, for testing without steering through the rows.
     */
    private void openDebugTitle(Intent intent) {
        String type = intent.getStringExtra("debug_type");
        String id = intent.getStringExtra("debug_id");
        if (!BuildConfig.DEBUG || type == null || id == null) return;
        startActivity(new Intent(this, DetailsActivity.class)
                .putExtra("type", type).putExtra("id", id).putExtra("name", id));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mClockTick);
        buildMenu();
        // Addons and catalogs may have changed on the web page.
        if (!pageKeys(mPage).equals(mPageKeys)) showPage(mPage);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mClockTick);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("page", mPage);
    }

    @Override
    protected void onDestroy() {
        mRows.destroy();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Back goes up to the menu, then Home, then leaves.
            if (mRowsView.hasFocus()) {
                focusMenu();
                return true;
            }
            if (!PAGE_HOME.equals(mPage)) {
                showPage(PAGE_HOME);
                focusMenu();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Menu ----

    private void buildMenu() {
        mMenu.removeAllViews();
        addMenuItem(PAGE_HOME, "Home");
        for (AddonStore.HubInfo hub : Stremio.addons(this).hubsInUse()) addMenuItem(hub.id, hub.name);
        addMenuItem(PAGE_LIVE, "Live TV");
        addMenuItem(PAGE_SETTINGS, "Settings");
        // A hub that's gone (no catalogs left) falls back to Home.
        if (mMenu.findViewWithTag(mPage) == null) mPage = PAGE_HOME;
        markMenu();
    }

    private void addMenuItem(String page, String label) {
        TextView item = (TextView) LayoutInflater.from(this).inflate(R.layout.menu_item, mMenu, false);
        item.setText(label);
        item.setTag(page);
        item.setOnClickListener(v -> openPage(page));
        mMenu.addView(item);
    }

    private void markMenu() {
        for (int i = 0; i < mMenu.getChildCount(); i++) {
            View item = mMenu.getChildAt(i);
            item.setSelected(mPage.equals(item.getTag()));
        }
    }

    private void focusMenu() {
        View item = mMenu.findViewWithTag(mPage);
        if (item != null) item.requestFocus();
    }

    private void openPage(String page) {
        if (PAGE_LIVE.equals(page)) {
            startActivity(new Intent(this, GuideActivity.class));
        } else if (PAGE_SETTINGS.equals(page)) {
            startActivity(new Intent(this, MainActivity.class));
        } else if (!page.equals(mPage)) {
            showPage(page);
        }
    }

    // ---- Pages ----

    private List<CatalogRows.Row> pageRows(String page) {
        AddonStore store = Stremio.addons(this);
        List<CatalogRows.Row> rows = new ArrayList<>();
        if (PAGE_HOME.equals(page)) {
            for (AddonStore.Entry e : store.homeCatalogs()) {
                rows.add(new CatalogRows.Row(homeTitle(e), e));
            }
        } else {
            for (AddonStore.Entry e : store.hubCatalogs(page)) {
                rows.add(new CatalogRows.Row(e.addon.displayName(e.catalog), e));
            }
        }
        return rows;
    }

    /** On Home, rows of different types can share a name ("Popular"), so the type is added. */
    private static String homeTitle(AddonStore.Entry e) {
        String name = e.addon.displayName(e.catalog);
        String type = "movie".equals(e.catalog.type) ? "Movies"
                : "series".equals(e.catalog.type) ? "TV" : null;
        if (type == null || name.toLowerCase(Locale.ROOT).contains(type.toLowerCase(Locale.ROOT))) {
            return name;
        }
        return name + " · " + type;
    }

    private List<String> pageKeys(String page) {
        List<String> keys = new ArrayList<>();
        for (CatalogRows.Row row : pageRows(page)) keys.add(row.title + "|" + row.entry.key());
        return keys;
    }

    private void showPage(String page) {
        mPage = page;
        markMenu();
        mPageKeys = pageKeys(page);
        mHeroItem = null;
        bindHero(null);
        mRows.setRows(pageRows(page));
    }

    private void updateEmpty() {
        boolean empty = mRows.shownCount() == 0;
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        if (Stremio.addons(this).all().isEmpty()) {
            mEmpty.setText("Add your Stremio addons to fill this page.\n\n"
                    + "Open Settings for the web page's address and PIN, then use its Addons tab.");
        } else if (!mRows.allLoaded()) {
            mEmpty.setText("Loading…");
        } else {
            mEmpty.setText("Nothing to show here.\n\n"
                    + "Choose catalogs for this page on the web page's Catalogs tab.");
        }
    }

    // ---- Hero ----

    @Override
    public void onItemFocused(MetaItem item) {
        mPendingHero = item;
        mHandler.removeCallbacks(mShowHero);
        mHandler.postDelayed(mShowHero, HERO_DELAY_MS);
    }

    @Override
    public void onItemClicked(MetaItem item) {
        startActivity(DetailsActivity.intent(this, item));
    }

    private void bindHero(MetaItem item) {
        if (item == mHeroItem && item != null) return;
        mHeroItem = item;
        if (item == null) {
            Images.fade(mBackdrop, null);
            Images.load(mHeroLogo, null);
            mHeroTitle.setText(null);
            mHeroMeta.setText(null);
            mHeroDesc.setText(null);
            return;
        }
        Images.fade(mBackdrop, item.background.isEmpty() ? item.poster : item.background);
        mHeroTitle.setText(item.name);
        mHeroTitle.setVisibility(item.logo.isEmpty() ? View.VISIBLE : View.GONE);
        mHeroLogo.setVisibility(item.logo.isEmpty() ? View.GONE : View.VISIBLE);
        Images.fade(mHeroLogo, item.logo);
        mHeroMeta.setText(metaLine(item));
        mHeroDesc.setText(item.description);
    }

    /** "2010 · 2h 28m · ★ 8.8 · Action, Sci-Fi". */
    static String metaLine(MetaItem item) {
        List<String> parts = new ArrayList<>();
        if (!item.releaseInfo.isEmpty()) parts.add(item.releaseInfo);
        int runtime = item.runtimeSeconds();
        if (runtime > 0) {
            int minutes = runtime / 60;
            parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
        }
        if (!item.certification.isEmpty()) parts.add(item.certification);
        if (item.imdbRating > 0) parts.add(String.format(Locale.ROOT, "★ %.1f", item.imdbRating));
        if (!item.genres.isEmpty()) {
            parts.add(String.join(", ", item.genres.subList(0, Math.min(3, item.genres.size()))));
        }
        return String.join("  ·  ", parts);
    }
}
