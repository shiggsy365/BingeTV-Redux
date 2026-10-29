package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.Intent;

import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Stream;
import com.shiggsy365.bingetvredux.stremio.Subtitles;
import com.shiggsy365.bingetvredux.stremio.Video;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A film or episode to play: what it is, where to start, and (once StreamsActivity has found
 * them) its streams in the order to try and its subtitles. Handed from screen to screen here
 * rather than in intents: streams carry headers and long descriptions, and the show's whole
 * episode list comes along for Up Next.
 */
public final class Playback {
    public final MetaItem meta;
    /** The episode; null for a film. */
    public final Video video;
    public final long startMs;
    /** For a next episode: the source group the last one came from, tried first. */
    public final String bingeGroup;

    public List<Stream> streams = new ArrayList<>();
    /** The stream to start with; if it fails, the next ones are tried in turn. */
    public int start;
    public List<Subtitles.Track> subtitles = new ArrayList<>();

    private static Playback sCurrent;

    public Playback(MetaItem meta, Video video, long startMs, String bingeGroup) {
        this.meta = meta;
        this.video = video;
        this.startMs = startMs;
        this.bingeGroup = bingeGroup == null ? "" : bingeGroup;
    }

    public String videoId() {
        return video != null ? video.id : meta.id;
    }

    public String title() {
        return meta.name;
    }

    /** "S1 E2  Title" for an episode, null for a film. */
    public String subtitle() {
        if (video == null) return null;
        String code = DetailsActivity.episodeCode(video);
        return video.title.isEmpty() || code.equals(video.title) ? code : code + "  " + video.title;
    }

    /** Finds the streams (StreamsActivity), then plays or lists them. */
    public static void start(Context context, Playback playback, boolean choose) {
        set(playback);
        context.startActivity(new Intent(context, StreamsActivity.class)
                .putExtra(StreamsActivity.EXTRA_CHOOSE, choose));
    }

    /** What watch state stores for this, with the stream's source group. */
    public WatchStore.Entry entry(Stream stream) {
        JSONObject ids = new JSONObject();
        try {
            for (Map.Entry<String, String> e : meta.ids.entrySet()) ids.put(e.getKey(), e.getValue());
        } catch (JSONException ignored) {
            // No ids then.
        }
        return new WatchStore.Entry(videoId(), meta.type, meta.id,
                video == null ? -1 : video.season, video == null ? -1 : video.episode,
                video == null ? meta.name : video.title, meta.name, meta.poster, meta.background,
                meta.logo, stream == null ? "" : stream.bingeGroup, ids);
    }

    /** The episode after this one, if it has aired; null for a film or the last episode. */
    public Video nextEpisode() {
        if (video == null || video.season == Video.NONE) return null;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        boolean after = false;
        for (int season : meta.seasons()) {
            if (season == 0) continue;
            for (Video v : meta.episodes(season)) {
                if (after) return v.isReleased(today) ? v : null;
                if (v.id.equals(video.id)) after = true;
            }
        }
        return null;
    }

    static synchronized void set(Playback playback) {
        sCurrent = playback;
    }

    static synchronized Playback current() {
        return sCurrent;
    }
}
