package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;
import com.shiggsy365.bingetvredux.stremio.StremioException;
import com.shiggsy365.bingetvredux.stremio.Video;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Whether a show you've started has every aired episode watched, for its poster's ribbon. That
 * needs the show's episodes, so the first time a started show's poster is drawn its meta is
 * fetched in the background (usually from the cache), and rows are redrawn once it's known.
 */
final class ShowProgress {
    private static final long KEEP_MS = 6 * 3600_000L;

    private static final class Aired {
        final Set<String> episodes;
        final long at;

        Aired(Set<String> episodes, long at) {
            this.episodes = episodes;
            this.at = at;
        }
    }

    private static final Map<String, Aired> sAired = new HashMap<>();
    private static final Set<String> sPending = new HashSet<>();
    private static final List<Runnable> sListeners = new ArrayList<>();
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(2);
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private ShowProgress() {}

    /** Called on the main thread when a show's episodes become known. */
    static synchronized void addListener(Runnable listener) {
        sListeners.add(listener);
    }

    static synchronized void removeListener(Runnable listener) {
        sListeners.remove(listener);
    }

    /**
     * Whether every aired episode of a started show is watched. False while that isn't known
     * yet: the episodes are looked up, and listeners hear when they arrive.
     */
    static boolean fullyWatched(Context context, MetaItem show) {
        WatchStore store = WatchStore.get(context);
        if (!store.hasWatchedEpisodes(show.id)) return false;
        Set<String> aired;
        synchronized (ShowProgress.class) {
            Aired known = sAired.get(show.id);
            if (known == null || System.currentTimeMillis() - known.at > KEEP_MS) {
                fetch(context.getApplicationContext(), show);
                if (known == null) return false;
            }
            aired = known.episodes;
        }
        return !aired.isEmpty() && store.watchedEpisodes(show.id).containsAll(aired);
    }

    /** The aired episodes of a show whose meta is already at hand (e.g. its info page). */
    static void remember(MetaItem show) {
        Set<String> aired = airedEpisodes(show);
        synchronized (ShowProgress.class) {
            sAired.put(show.id, new Aired(aired, System.currentTimeMillis()));
        }
    }

    private static synchronized void fetch(Context context, MetaItem show) {
        if (!sPending.add(show.id)) return;
        sExecutor.execute(() -> {
            Set<String> aired = null;
            try {
                aired = airedEpisodes(Metas.fetch(Stremio.client(context), Stremio.addons(context),
                        show.type, show.id).meta);
            } catch (StremioException ignored) {
                // Tried again after KEEP_MS; the poster just has no ribbon until then.
            }
            List<Runnable> listeners;
            synchronized (ShowProgress.class) {
                sPending.remove(show.id);
                sAired.put(show.id, new Aired(aired == null ? new HashSet<>() : aired,
                        System.currentTimeMillis()));
                listeners = new ArrayList<>(sListeners);
            }
            if (aired != null && !aired.isEmpty()) {
                sMain.post(() -> {
                    for (Runnable r : listeners) r.run();
                });
            }
        });
    }

    private static Set<String> airedEpisodes(MetaItem show) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        Set<String> aired = new HashSet<>();
        for (Video v : show.videos) {
            if (v.season > 0 && v.episode != Video.NONE && v.isReleased(today)) {
                aired.add(v.season + ":" + v.episode);
            }
        }
        return aired;
    }
}
