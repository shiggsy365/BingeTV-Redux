package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.net.Uri;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;
import com.shiggsy365.bingetvredux.stremio.Parallel;
import com.shiggsy365.bingetvredux.stremio.StremioException;
import com.shiggsy365.bingetvredux.stremio.Video;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Home's own rows, ahead of the Home hub's catalogs: Continue Watching, Next Up, the MDBList
 * watchlist and what's on live TV now. Each can be turned off on the web page.
 */
final class HomeRows {
    /** Ids of "On now" items: "live:" and the channel's id. */
    static final String LIVE_PREFIX = "live:";
    private static final int CONTINUE_LIMIT = 50;
    // Enough recently watched shows to fill a row (rows show 50 at most).
    private static final int NEXT_UP_SHOWS = 60;
    private static final int ON_NOW_LIMIT = 30;
    private static final long NEXT_UP_TIMEOUT_MS = 20_000;
    static final String CONTINUE = "Continue Watching";
    static final String NEXT_UP = "Next Up";
    static final String WATCHLIST = "Watchlist";
    static final String ON_NOW = "On Now";
    /** The rows that change with what's been watched. */
    static final List<String> WATCH_ROWS = java.util.Arrays.asList(CONTINUE, NEXT_UP, WATCHLIST);
    // When the first programme shown in On Now ends (0: nothing shown), for its refresh.
    private static volatile long sOnNowChangesAt;

    private HomeRows() {}

    static List<CatalogRows.Row> rows(Context context) {
        Context app = context.getApplicationContext();
        List<CatalogRows.Row> rows = new ArrayList<>();
        boolean combined = Settings.bool(app, Settings.CONTINUE_WITH_NEXT_UP);
        if (Settings.bool(app, Settings.ROW_CONTINUE)) {
            rows.add(new CatalogRows.Row(CONTINUE, () -> combined
                    ? newestFirst(continueWatching(app), nextUp(app)) : items(continueWatching(app))));
        }
        if (Settings.bool(app, Settings.ROW_NEXT_UP) && !(combined && Settings.bool(app, Settings.ROW_CONTINUE))) {
            rows.add(new CatalogRows.Row(NEXT_UP, () -> items(nextUp(app))));
        }
        if (Settings.bool(app, Settings.ROW_WATCHLIST) && Mdblist.enabled(app)) {
            rows.add(new CatalogRows.Row(WATCHLIST, () -> withMetas(app, Mdblist.watchlist(app, false))));
        }
        if (Settings.bool(app, Settings.ROW_ON_NOW) && Settings.bool(app, Settings.LIVE_TV)) {
            rows.add(new CatalogRows.Row(ON_NOW, () -> onNow(app)));
        }
        return rows;
    }

    /** Whether a programme On Now shows has ended since it loaded. */
    static boolean onNowStale() {
        long at = sOnNowChangesAt;
        return at > 0 && System.currentTimeMillis() >= at;
    }

    /** A row's item and when it was last active (for the combined row's order). */
    static final class Timed {
        final long at;
        final MetaItem item;

        Timed(long at, MetaItem item) {
            this.at = at;
            this.item = item;
        }
    }

    private static List<MetaItem> items(List<Timed> timed) {
        List<MetaItem> out = new ArrayList<>();
        for (Timed t : timed) out.add(t.item);
        return out;
    }

    /** Part-watched items and next episodes in one row, most recently active first. */
    static List<MetaItem> newestFirst(List<Timed> a, List<Timed> b) {
        List<Timed> all = new ArrayList<>(a);
        all.addAll(b);
        java.util.Collections.sort(all, (x, y) -> Long.compare(y.at, x.at));
        List<MetaItem> out = items(all);
        return out.size() > CONTINUE_LIMIT ? new ArrayList<>(out.subList(0, CONTINUE_LIMIT)) : out;
    }

    /**
     * Part-watched films and episodes, each with its film's or show's meta from the addons (as
     * BingeTV shows them): the episode's plot, and what's left of it.
     */
    private static List<Timed> continueWatching(Context context) {
        List<Callable<Timed>> tasks = new ArrayList<>();
        for (WatchStore.Row r : WatchStore.get(context).continueWatching(CONTINUE_LIMIT)) {
            tasks.add(() -> new Timed(r.updatedAt, resumeItem(context, r)));
        }
        List<Timed> out = new ArrayList<>();
        for (Parallel.Result<Timed> r : Parallel.run(tasks, NEXT_UP_TIMEOUT_MS)) {
            if (r.ok() && r.value != null && r.value.item != null) out.add(r.value);
        }
        return out;
    }

    private static MetaItem resumeItem(Context context, WatchStore.Row r) throws Exception {
        WatchStore.Entry e = r.entry;
        String left = DetailsActivity.clock(Math.max(0, r.durationMs - r.positionMs)) + " left";
        String code = String.format(Locale.ROOT, "S%d E%d", e.season, e.episode);
        String id = e.metaId.isEmpty() ? e.videoId : e.metaId;
        MetaItem meta = null;
        try {
            meta = Metas.fetch(Stremio.client(context), Stremio.addons(context), e.type, id).meta;
        } catch (StremioException ignored) {
            // What was saved when it was played still makes a card.
        }
        if (meta == null) {
            String what = e.isEpisode() ? code + (e.title.isEmpty() ? "" : "  " + e.title)
                    + "  ·  " + left : left;
            MetaItem saved = item(id, e.type, e.name, e.poster, e.background, e.logo, what);
            return saved == null ? null : saved.forRow(what, "");
        }
        if (!e.isEpisode()) return meta.forRow(left, "");
        Video v = meta.video(e.videoId);
        String title = v != null && !v.title.isEmpty() ? v.title : e.title;
        return meta.forRow(code + (title.isEmpty() ? "" : "  ·  " + title) + "  ·  " + left,
                v != null ? v.overview : "");
    }

    /** Watchlist items with their metas from the addons; MDBList's own details if there's none. */
    private static List<MetaItem> withMetas(Context context, List<MetaItem> items) {
        List<Callable<MetaItem>> tasks = new ArrayList<>();
        for (MetaItem m : items) {
            tasks.add(() -> Metas.fetch(Stremio.client(context), Stremio.addons(context), m.type, m.id)
                    .meta.forRow("", ""));
        }
        List<Parallel.Result<MetaItem>> results = Parallel.run(tasks, NEXT_UP_TIMEOUT_MS);
        List<MetaItem> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Parallel.Result<MetaItem> r = results.get(i);
            out.add(r.ok() && r.value != null ? r.value : items.get(i));
        }
        return out;
    }

    /**
     * For each show recently watched, the episode after the furthest one watched, if it has
     * aired and nothing of the show is part-watched (that's Continue Watching's).
     */
    private static List<Timed> nextUp(Context context) {
        WatchStore store = WatchStore.get(context);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        List<Callable<Timed>> tasks = new ArrayList<>();
        for (String[] show : store.recentShows(NEXT_UP_SHOWS)) {
            if (store.resumeFor(show[0]) != null) continue;
            tasks.add(() -> {
                MetaItem meta = Metas.fetch(Stremio.client(context), Stremio.addons(context),
                        show[1], show[0]).meta;
                Video next = next(meta, store.watchedEpisodes(show[0]), today);
                if (next == null) return null;
                String what = "Next: " + DetailsActivity.episodeCode(next)
                        + (next.title.isEmpty() ? "" : "  ·  " + next.title);
                return new Timed(Long.parseLong(show[2]), meta.forRow(what, next.overview));
            });
        }
        List<Timed> out = new ArrayList<>();
        for (Parallel.Result<Timed> r : Parallel.run(tasks, NEXT_UP_TIMEOUT_MS)) {
            if (r.ok() && r.value != null) out.add(r.value);
        }
        return out;
    }

    /** The episode after the furthest one watched, if it has aired; specials don't count. */
    static Video next(MetaItem meta, Set<String> watched, String today) {
        List<Video> episodes = new ArrayList<>();
        for (int season : meta.seasons()) if (season != 0) episodes.addAll(meta.episodes(season));
        int last = -1;
        for (int i = 0; i < episodes.size(); i++) {
            Video v = episodes.get(i);
            if (watched.contains(v.season + ":" + v.episode)) last = i;
        }
        if (last < 0) return null;
        for (int i = last + 1; i < episodes.size(); i++) {
            Video v = episodes.get(i);
            if (!watched.contains(v.season + ":" + v.episode)) {
                return v.isReleased(today) ? v : null;
            }
        }
        return null;
    }

    /** Live channels with what's on, recently watched first: the logo as a square tile, and a mini
     *  guide (what's on now and next) above the current program's description, and the
     *  program's own picture, when the guide has one, as the backdrop. */
    private static List<MetaItem> onNow(Context context) throws JSONException {
        List<MetaItem> out = new ArrayList<>();
        long changesAt = 0;
        for (GuideData.Channel c : GuideData.onNow(context, Settings.recentChannels(context),
                ON_NOW_LIMIT)) {
            File logo = GuideStore.logoFile(context, (int) c.id);
            String logoUri = logo.exists() ? Uri.fromFile(logo).toString() : "";
            JSONObject data = new JSONObject()
                    .put("id", LIVE_PREFIX + c.id).put("type", "tv").put("name", c.name)
                    .put("poster", logoUri).put("logo", logoUri)
                    .put("background", c.now.image == null ? "" : c.now.image)
                    .put("posterShape", "square")
                    .put("description", c.now.description);
            String guide = "On Now – " + c.now.title
                    + (c.next != null ? "\nOn Next – " + c.next.title : "");
            MetaItem item = MetaItem.preview(data, null);
            if (item != null) out.add(item.forRow(guide, c.now.description));
            changesAt = changesAt == 0 ? c.now.endMs : Math.min(changesAt, c.now.endMs);
        }
        sOnNowChangesAt = changesAt;
        return out;
    }

    private static MetaItem item(String id, String type, String name, String poster,
            String background, String logo, String description) throws JSONException {
        return MetaItem.preview(new JSONObject().put("id", id).put("type", type).put("name", name)
                .put("poster", poster).put("background", background).put("logo", logo)
                .put("description", description), null);
    }
}
