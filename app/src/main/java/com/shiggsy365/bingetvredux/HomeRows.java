package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.net.Uri;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;
import com.shiggsy365.bingetvredux.stremio.Parallel;
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
    private static final int CONTINUE_LIMIT = 20;
    private static final int NEXT_UP_SHOWS = 15;
    private static final int ON_NOW_LIMIT = 30;
    private static final long NEXT_UP_TIMEOUT_MS = 20_000;

    private HomeRows() {}

    static List<CatalogRows.Row> rows(Context context) {
        Context app = context.getApplicationContext();
        List<CatalogRows.Row> rows = new ArrayList<>();
        if (Settings.bool(app, Settings.ROW_CONTINUE)) {
            rows.add(new CatalogRows.Row("Continue Watching", () -> continueWatching(app)));
        }
        if (Settings.bool(app, Settings.ROW_NEXT_UP)) {
            rows.add(new CatalogRows.Row("Next Up", () -> nextUp(app)));
        }
        if (Settings.bool(app, Settings.ROW_WATCHLIST) && Mdblist.enabled(app)) {
            rows.add(new CatalogRows.Row("Watchlist", () -> Mdblist.watchlist(app, false)));
        }
        if (Settings.bool(app, Settings.ROW_ON_NOW)) {
            rows.add(new CatalogRows.Row("On Now", () -> onNow(app)));
        }
        return rows;
    }

    private static List<MetaItem> continueWatching(Context context) throws JSONException {
        List<MetaItem> out = new ArrayList<>();
        for (WatchStore.Row r : WatchStore.get(context).continueWatching(CONTINUE_LIMIT)) {
            WatchStore.Entry e = r.entry;
            String left = DetailsActivity.clock(Math.max(0, r.durationMs - r.positionMs)) + " left";
            String what = e.isEpisode() ? String.format(Locale.ROOT, "S%d E%d", e.season, e.episode)
                    + (e.title.isEmpty() ? "" : "  " + e.title) + "  ·  " + left : left;
            MetaItem item = item(e.metaId.isEmpty() ? e.videoId : e.metaId, e.type, e.name,
                    e.poster, e.background, e.logo, what);
            if (item != null) out.add(item);
        }
        return out;
    }

    /**
     * For each show recently watched, the episode after the furthest one watched, if it has
     * aired and nothing of the show is part-watched (that's Continue Watching's).
     */
    private static List<MetaItem> nextUp(Context context) {
        WatchStore store = WatchStore.get(context);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        List<Callable<MetaItem>> tasks = new ArrayList<>();
        for (String[] show : store.recentShows(NEXT_UP_SHOWS)) {
            if (store.resumeFor(show[0]) != null) continue;
            tasks.add(() -> {
                MetaItem meta = Metas.fetch(Stremio.client(context), Stremio.addons(context),
                        show[1], show[0]).meta;
                Video next = next(meta, store.watchedEpisodes(show[0]), today);
                if (next == null) return null;
                String what = "Next: " + DetailsActivity.episodeCode(next)
                        + (next.title.isEmpty() ? "" : "  " + next.title);
                return item(meta.id, meta.type, meta.name, meta.poster, meta.background,
                        meta.logo, what);
            });
        }
        List<MetaItem> out = new ArrayList<>();
        for (Parallel.Result<MetaItem> r : Parallel.run(tasks, NEXT_UP_TIMEOUT_MS)) {
            if (r.ok() && r.value != null) out.add(r.value);
        }
        return out;
    }

    private static Video next(MetaItem meta, Set<String> watched, String today) {
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

    /** Live channels with what's on, from every group. */
    private static List<MetaItem> onNow(Context context) throws JSONException {
        List<MetaItem> out = new ArrayList<>();
        List<GuideData.Group> groups = GuideData.load(context);
        if (groups.isEmpty()) return out;
        for (GuideData.Channel c : groups.get(0).channels) {
            if (c.now == null) continue;
            File logo = GuideStore.logoFile(context, (int) c.id);
            JSONObject data = new JSONObject()
                    .put("id", LIVE_PREFIX + c.id).put("type", "tv").put("name", c.name)
                    .put("poster", logo.exists() ? Uri.fromFile(logo).toString() : "")
                    .put("posterShape", "landscape")
                    .put("description", c.now.title);
            MetaItem item = MetaItem.preview(data, null);
            if (item != null) out.add(item);
            if (out.size() >= ON_NOW_LIMIT) break;
        }
        return out;
    }

    private static MetaItem item(String id, String type, String name, String poster,
            String background, String logo, String description) throws JSONException {
        return MetaItem.preview(new JSONObject().put("id", id).put("type", type).put("name", name)
                .put("poster", poster).put("background", background).put("logo", logo)
                .put("description", description), null);
    }
}
