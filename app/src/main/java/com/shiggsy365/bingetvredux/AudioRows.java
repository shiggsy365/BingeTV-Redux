package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Context;

import com.shiggsy365.bingetvredux.stremio.MetaItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Radio and Podcasts pages' rows (on Home's menu bar), and what their cards do: a station
 * or an episode plays, a podcast opens its page.
 */
final class AudioRows {
    static final String FAVOURITES = "Favourite stations";
    static final String CONTINUE = "Continue listening";
    static final String SUBSCRIPTIONS = "Your podcasts";
    static final String LATEST = "Latest episodes";
    /** Rows that change with what's done on the TV, reloaded when the page comes back. */
    static final List<String> CHANGING = Arrays.asList(FAVOURITES, CONTINUE, SUBSCRIPTIONS, LATEST);

    private AudioRows() {}

    static List<CatalogRows.Row> radio(Context context) {
        Context app = context.getApplicationContext();
        String country = Radio.country(app);
        List<CatalogRows.Row> rows = new ArrayList<>();
        rows.add(new CatalogRows.Row(FAVOURITES, () -> Radio.toMetas(Radio.favourites(app))));
        rows.add(new CatalogRows.Row("Popular in " + Radio.countryName(country),
                () -> Radio.toMetas(Radio.top(country))));
        rows.add(new CatalogRows.Row("Popular worldwide", () -> Radio.toMetas(Radio.topWorldwide())));
        for (String genre : Radio.GENRES) {
            rows.add(new CatalogRows.Row(genreTitle(genre), () -> Radio.toMetas(Radio.genre(genre, country))));
        }
        return rows;
    }

    private static String genreTitle(String tag) {
        switch (tag) {
            case "hiphop": return "Hip hop";
            case "chillout": return "Chill out";
            default: return tag.substring(0, 1).toUpperCase(Locale.ROOT) + tag.substring(1);
        }
    }

    static List<CatalogRows.Row> podcasts(Context context) {
        Context app = context.getApplicationContext();
        String country = Radio.country(app);
        List<CatalogRows.Row> rows = new ArrayList<>();
        rows.add(new CatalogRows.Row(CONTINUE, () -> Podcasts.episodeMetas(app, Podcasts.inProgress(app))));
        rows.add(new CatalogRows.Row(LATEST, () -> Podcasts.episodeMetas(app, Podcasts.latest(app))));
        rows.add(new CatalogRows.Row(SUBSCRIPTIONS, () -> Podcasts.podcastMetas(Podcasts.subscriptions(app))));
        rows.add(new CatalogRows.Row("Top podcasts in " + Radio.countryName(country),
                () -> Podcasts.podcastMetas(Podcasts.top(country))));
        return rows;
    }

    static boolean isAudio(MetaItem item) {
        return item.id.startsWith(Radio.PREFIX) || item.id.startsWith(Podcasts.PODCAST_PREFIX)
                || item.id.startsWith(Podcasts.EPISODE_PREFIX);
    }

    /** A card chosen: plays or opens it. Returns false if it isn't a radio or podcast card. */
    static boolean open(Activity activity, MetaItem item) {
        if (item.id.startsWith(Radio.PREFIX)) {
            AudioPlayer.Item station = Radio.station(activity, item.id);
            if (station != null) NowPlayingActivity.play(activity, station, 0);
            return true;
        }
        if (item.id.startsWith(Podcasts.EPISODE_PREFIX)) {
            AudioPlayer.Item episode = Podcasts.episode(item.id);
            if (episode != null) {
                NowPlayingActivity.play(activity, episode, Podcasts.resumeAt(activity, episode.id));
            }
            return true;
        }
        if (item.id.startsWith(Podcasts.PODCAST_PREFIX)) {
            activity.startActivity(PodcastActivity.intent(activity,
                    item.id.substring(Podcasts.PODCAST_PREFIX.length())));
            return true;
        }
        return false;
    }
}
