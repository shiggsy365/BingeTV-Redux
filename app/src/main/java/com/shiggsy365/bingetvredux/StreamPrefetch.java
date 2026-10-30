package com.shiggsy365.bingetvredux;

import android.content.Context;

import com.shiggsy365.bingetvredux.stremio.Streams;
import com.shiggsy365.bingetvredux.stremio.Subtitles;

import java.util.List;
import java.util.concurrent.Future;

/**
 * The next episode's streams and subtitles, asked for while Up Next shows, so it starts
 * without the wait. One at a time; StreamsActivity takes it if it's for what it's finding.
 */
final class StreamPrefetch {
    // Links from addons can expire; an old prefetch is fetched again.
    private static final long MAX_AGE_MS = 10 * 60_000L;
    private static final Background.Tasks sTasks = new Background.Tasks(2);

    static final class Found {
        final String key;
        final long at;
        final Future<Streams.Found> streams;
        final Future<List<Subtitles.Track>> subtitles;

        Found(String key, long at, Future<Streams.Found> streams,
                Future<List<Subtitles.Track>> subtitles) {
            this.key = key;
            this.at = at;
            this.streams = streams;
            this.subtitles = subtitles;
        }
    }

    private static Found sFound;

    private StreamPrefetch() {}

    static synchronized void start(Context context, String type, String id) {
        String key = type + "/" + id;
        if (sFound != null && sFound.key.equals(key)) return;
        Context app = context.getApplicationContext();
        List<String> languages = Settings.subtitleLanguages(app);
        sFound = new Found(key, System.currentTimeMillis(),
                sTasks.submit(() -> Streams.fetch(Stremio.client(app), Stremio.addons(app), type, id)),
                sTasks.submit(() -> Subtitles.fetch(Stremio.client(app), Stremio.addons(app), type,
                        id, null, languages)));
    }

    /** The prefetch for this title, taken (so it's used once); null if there's none. */
    static synchronized Found take(String type, String id) {
        Found found = sFound;
        sFound = null;
        if (found == null || !found.key.equals(type + "/" + id)
                || System.currentTimeMillis() - found.at > MAX_AGE_MS) {
            return null;
        }
        return found;
    }
}
