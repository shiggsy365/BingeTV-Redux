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
    static final String PAGE_HOME = AddonStore.HOME;
    private static final String PAGE_LIVE = "live";
    private static final String PAGE_SEARCH = "search";
    // Whether "Who's watching?" has been asked since the app started.
    private static boolean sProfileChosen;
    private static final long HERO_DELAY_MS = 150;
    private static final long CLOCK_TICK_MS = 15_000;

    private LinearLayout mMenu;
    private TextView mClock;
    private ImageView mBackdrop;
    private ImageView mBackdropFull;
    private ImageView mHeroLogo;
    private TextView mHeroTitle;
    private TextView mHeroSubtitle;
    private LinearLayout mHeroFacts;
    private LinearLayout mHeroGenres;
    private TextView mHeroDesc;
    private TextView mHeroSubtitle2;
    private TextView mEmpty;
    private VerticalGridView mRowsView;
    private CatalogRows mRows;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DateFormat mTimeFormat;
    private String mPage = PAGE_HOME;
    // The catalogs on the page, to tell when the web page has changed them.
    private List<String> mPageKeys = new ArrayList<>();
    // The profile and watch state the page was built for: either changing rebuilds it.
    private String mPageState = "";
    private MetaItem mHeroItem;
    private View mHero;
    private View mBackdropDim;
    // The menu bar has the focus: one random title of the first row fills the screen, dimmed,
    // without the windowed artwork or the title's details.
    private boolean mMenuMode;
    private MetaItem mPendingHero;
    private final Runnable mShowHero = () -> bindHero(mPendingHero);
    private HeroTrailer mTrailer;
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
        View logo = findViewById(R.id.menu_logo);
        logo.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        logo.setOnFocusChangeListener((v, focused) -> {
            findViewById(R.id.menu_logo_underline).animate().alpha(focused ? 1f : 0f)
                    .setDuration(160).start();
            v.post(this::updateMenuMode);
        });
        mClock = findViewById(R.id.clock);
        mBackdrop = findViewById(R.id.backdrop);
        mBackdropFull = findViewById(R.id.backdrop_full);
        mHero = findViewById(R.id.hero);
        mBackdropDim = findViewById(R.id.backdrop_dim);
        mHeroLogo = findViewById(R.id.hero_logo);
        mHeroTitle = findViewById(R.id.hero_title);
        mHeroSubtitle = findViewById(R.id.hero_subtitle);
        mHeroFacts = findViewById(R.id.hero_facts);
        mHeroGenres = findViewById(R.id.hero_genres);
        mHeroDesc = findViewById(R.id.hero_desc);
        mHeroSubtitle2 = findViewById(R.id.hero_subtitle2);
        mEmpty = findViewById(R.id.empty);
        mRowsView = findViewById(R.id.rows);
        mRows = new CatalogRows(this, mRowsView, this);
        mRows.setOnChanged(this::updateEmpty);
        mTrailer = new HeroTrailer(this, findViewById(R.id.hero_video), mBackdrop, mBackdropFull,
                findViewById(R.id.clock));
        if (savedInstanceState != null) mPage = savedInstanceState.getString("page", PAGE_HOME);
        if (!sProfileChosen && Profiles.all(this).size() > 1) {
            startActivity(new Intent(this, ProfilesActivity.class));
        }
        sProfileChosen = true;
        openDebugTitle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openDebugTitle(intent);
    }

    /**
     * {@code am start -n …/.HomeActivity --es debug_type movie --es debug_id tt…} opens that
     * title's info page, for testing and measuring without steering through the rows.
     */
    private void openDebugTitle(Intent intent) {
        String type = intent.getStringExtra("debug_type");
        String id = intent.getStringExtra("debug_id");
        if (type == null || id == null) return;
        startActivity(new Intent(this, DetailsActivity.class)
                .putExtra("type", type).putExtra("id", id).putExtra("name", id));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mClockTick);
        buildMenu();
        Mdblist.syncIfDue(this);
        Updates.checkIfDue(this);
        // Addons and catalogs may have changed on the web page, and what's been watched.
        if (!pageKeys(mPage).equals(mPageKeys) || !pageState().equals(mPageState)) {
            showPage(mPage);
        }
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mClockTick);
        mTrailer.stop();
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
        mTrailer.destroy();
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
        AddonStore store = Stremio.addons(this);
        String homeIcon = "";
        for (AddonStore.HubInfo hub : store.hubs()) if (AddonStore.HOME.equals(hub.id)) homeIcon = hub.icon;
        addMenuItem(PAGE_HOME, "Home", hubIconPath(AddonStore.HOME, homeIcon));
        for (AddonStore.HubInfo hub : store.hubsInUse()) {
            if (!AddonStore.HOME.equals(hub.id)) addMenuItem(hub.id, hub.name, hubIconPath(hub.id, hub.icon));
        }
        if (Settings.bool(this, Settings.LIVE_TV)) addMenuItem(PAGE_LIVE, "Live TV", MenuIcons.LIVE);
        addMenuItem(PAGE_SEARCH, "Search", MenuIcons.SEARCH);
        // Profiles and settings are behind the logo (MainActivity).
        // A hub that's gone (no catalogs left) falls back to Home.
        if (mMenu.findViewWithTag(mPage) == null) mPage = PAGE_HOME;
        markMenu();
    }

    private static String hubIconPath(String hubId, String icon) {
        return MenuIcons.hubPath(icon.isEmpty() ? MenuIcons.defaultFor(hubId) : icon);
    }

    private void addMenuItem(String page, String label, String iconPath) {
        View item = LayoutInflater.from(this).inflate(R.layout.menu_item, mMenu, false);
        ((ImageView) item.findViewById(R.id.menu_icon)).setImageDrawable(
                MenuIcons.drawable(this, iconPath, 22));
        ((TextView) item.findViewById(R.id.menu_label)).setText(label);
        item.setContentDescription(label);
        item.setTag(page);
        item.setOnClickListener(v -> openPage(page));
        item.setOnFocusChangeListener((v, focused) -> {
            // The bar widens smoothly as the focused item shows its name.
            android.transition.TransitionManager.beginDelayedTransition(mMenu,
                    new android.transition.ChangeBounds().setDuration(160));
            styleMenuItem(v);
            // Once the focus has settled: still somewhere on the bar, or gone to the rows.
            v.post(this::updateMenuMode);
        });
        mMenu.addView(item);
    }

    private void markMenu() {
        for (int i = 0; i < mMenu.getChildCount(); i++) styleMenuItem(mMenu.getChildAt(i));
    }

    /**
     * Focused: its name shows, beside a white icon, over the red underline. The page on screen:
     * a white icon. Others: a dimmer icon.
     */
    private void styleMenuItem(View item) {
        boolean focused = item.hasFocus();
        boolean current = mPage.equals(item.getTag());
        item.findViewById(R.id.menu_label).setVisibility(focused ? View.VISIBLE : View.GONE);
        ((ImageView) item.findViewById(R.id.menu_icon)).setColorFilter(
                focused || current ? 0xFFFFFFFF : 0xB3FFFFFF);
        View underline = item.findViewById(R.id.menu_underline);
        underline.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
    }

    private void focusMenu() {
        View item = mMenu.findViewWithTag(mPage);
        if (item != null) item.requestFocus();
    }

    private void openPage(String page) {
        if (PAGE_LIVE.equals(page)) {
            startActivity(new Intent(this, GuideActivity.class));
        } else if (PAGE_SEARCH.equals(page)) {
            startActivity(new Intent(this, SearchActivity.class));
        } else if (!page.equals(mPage)) {
            showPage(page);
        }
    }

    // ---- Pages ----

    private List<CatalogRows.Row> pageRows(String page) {
        AddonStore store = Stremio.addons(this);
        List<CatalogRows.Row> rows = new ArrayList<>();
        if (PAGE_HOME.equals(page)) {
            rows.addAll(HomeRows.rows(this));
            for (AddonStore.Entry e : store.hubCatalogs(AddonStore.HOME)) {
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
        for (CatalogRows.Row row : pageRows(page)) {
            keys.add(row.title + "|" + (row.entry == null ? "" : row.entry.key()));
        }
        return keys;
    }

    private void showPage(String page) {
        mPage = page;
        markMenu();
        mPageKeys = pageKeys(page);
        mPageState = pageState();
        mHeroItem = null;
        bindHero(null);
        mRows.setRows(pageRows(page));
        // On the menu bar, the artwork comes from this page's rows (e.g. another profile's).
        if (mMenuMode) {
            Images.fade(mBackdropFull, null);
            pickMenuArt(0);
        }
    }

    private void updateEmpty() {
        boolean empty = mRows.shownCount() == 0;
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        if (Stremio.addons(this).all().isEmpty()) {
            mEmpty.setText("Add your Stremio addons to fill this page.\n\n"
                    + "Select the logo at the top left for the web page's address and PIN, then use its Addons page.");
        } else if (!mRows.allLoaded()) {
            mEmpty.setText("Loading…");
        } else {
            mEmpty.setText("Nothing to show here.\n\n"
                    + "Move catalogs into this hub on the web page's Catalogs & hubs page.");
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
        if (item.id.startsWith(HomeRows.LIVE_PREFIX)) {
            startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_GROUP_ID, GuideData.ALL_GROUP_ID)
                    .putExtra(PlayerActivity.EXTRA_CHANNEL_ID,
                            Long.parseLong(item.id.substring(HomeRows.LIVE_PREFIX.length()))));
            return;
        }
        startActivity(DetailsActivity.intent(this, item));
    }

    private String pageState() {
        return Profiles.current(this).id + "|" + WatchStore.get(this).version();
    }

    private void updateMenuMode() {
        boolean onBar = mMenu.hasFocus() || findViewById(R.id.menu_logo).hasFocus();
        if (isDestroyed() || onBar == mMenuMode) return;
        mMenuMode = onBar;
        long ms = 250;
        if (mMenuMode) {
            mHandler.removeCallbacks(mShowHero);
            mTrailer.show(null);
            pickMenuArt(0);
            mBackdrop.animate().alpha(0f).setDuration(ms).start();
            mHero.animate().alpha(0f).setDuration(ms).start();
            mBackdropDim.animate().alpha(0.7f).setDuration(ms).start();
        } else {
            mBackdrop.animate().alpha(1f).setDuration(ms).start();
            mHero.animate().alpha(1f).setDuration(ms).start();
            mBackdropDim.animate().alpha(1f).setDuration(ms).start();
            // Back to the focused title's two layers.
            MetaItem item = mPendingHero != null ? mPendingHero : mHeroItem;
            mHeroItem = null;
            bindHero(item);
        }
    }

    /**
     * While the menu bar has focus: a random title of the first row, full screen. The rows may
     * still be loading (after a profile switch, say), so it tries again for a few seconds.
     */
    private void pickMenuArt(int attempt) {
        if (!mMenuMode || isDestroyed()) return;
        MetaItem pick = mRows.randomFirstItem();
        if (pick != null) {
            Images.fade(mBackdropFull, pick.background.isEmpty() ? pick.poster : pick.background);
        } else if (attempt < 20) {
            mHandler.postDelayed(() -> pickMenuArt(attempt + 1), 500);
        }
    }

    private void bindHero(MetaItem item) {
        if (mMenuMode) {
            // Shown when the focus goes back to the rows.
            mPendingHero = item;
            return;
        }
        if (item == mHeroItem && item != null) return;
        mHeroItem = item;
        mTrailer.show(item);
        if (item == null) {
            Images.fade(mBackdrop, null);
            Images.fadeSoft(mBackdropFull, null);
            Images.load(mHeroLogo, null);
            mHeroTitle.setText(null);
            mHeroSubtitle.setVisibility(View.GONE);
            mHeroSubtitle2.setVisibility(View.GONE);
            InfoChips.fill(mHeroFacts, new ArrayList<>());
            InfoChips.fill(mHeroGenres, new ArrayList<>());
            mHeroDesc.setText(null);
            return;
        }
        // A channel's backdrop is its program's picture; its logo isn't backdrop material.
        String art = !item.background.isEmpty() ? item.background
                : item.id.startsWith(HomeRows.LIVE_PREFIX) ? null : item.poster;
        Images.fade(mBackdrop, art);
        Images.fadeSoft(mBackdropFull, art);
        mHeroTitle.setText(item.name);
        mHeroTitle.setVisibility(item.logo.isEmpty() ? View.VISIBLE : View.GONE);
        mHeroLogo.setVisibility(item.logo.isEmpty() ? View.GONE : View.VISIBLE);
        Images.fade(mHeroLogo, item.logo);
        // A second line (a channel's "On Next") gets a line of its own, each cut short if long.
        String[] lines = item.subtitle.split("\n", 2);
        mHeroSubtitle.setText(lines[0]);
        mHeroSubtitle.setVisibility(lines[0].isEmpty() ? View.GONE : View.VISIBLE);
        mHeroSubtitle2.setText(lines.length > 1 ? lines[1] : null);
        mHeroSubtitle2.setVisibility(lines.length > 1 ? View.VISIBLE : View.GONE);
        InfoChips.fill(mHeroFacts, InfoChips.facts(item));
        InfoChips.fill(mHeroGenres, InfoChips.genres(item));
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
