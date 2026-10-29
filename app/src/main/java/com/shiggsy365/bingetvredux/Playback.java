package com.shiggsy365.bingetvredux;

import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.Subtitles;

import java.util.List;

/**
 * What VideoPlayerActivity is to play: a film or episode, its streams in the order to try them
 * and the subtitles found for it. Streams can carry headers and long descriptions, so they're
 * handed over here rather than in the intent.
 */
public final class Playback {
    public final String title;
    /** The episode ("S1 E2  Title"), or null for a film. */
    public final String subtitle;
    public final String type;
    public final String videoId;
    public final List<Stream> streams;
    /** The stream to start with; if it fails, the next ones are tried in turn. */
    public final int start;
    public final List<Subtitles.Track> subtitles;

    private static Playback sCurrent;

    public Playback(String title, String subtitle, String type, String videoId,
            List<Stream> streams, int start, List<Subtitles.Track> subtitles) {
        this.title = title;
        this.subtitle = subtitle;
        this.type = type;
        this.videoId = videoId;
        this.streams = streams;
        this.start = start;
        this.subtitles = subtitles;
    }

    static synchronized void set(Playback playback) {
        sCurrent = playback;
    }

    static synchronized Playback current() {
        return sCurrent;
    }
}
