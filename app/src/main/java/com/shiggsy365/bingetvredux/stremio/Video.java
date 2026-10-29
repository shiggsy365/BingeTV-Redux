package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An episode of a show, or an item of a channel. */
public final class Video {
    /** Season or episode number that isn't given. */
    public static final int NONE = -1;
    private static final Pattern DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})");

    public final String id;
    public final String title;
    public final int season;
    public final int episode;
    public final String released;
    public final String thumbnail;
    public final String overview;
    /** The episode's own rating, or 0. */
    public final double rating;

    private Video(String id, String title, int season, int episode, String released,
            String thumbnail, String overview, double rating) {
        this.id = id;
        this.title = title;
        this.season = season;
        this.episode = episode;
        this.released = released;
        this.thumbnail = thumbnail;
        this.overview = overview;
        this.rating = rating;
    }

    static Video fromJson(JSONObject data) {
        if (data == null || data.optString("id", "").isEmpty()) return null;
        String title = data.optString("title", "");
        if (title.isEmpty()) title = data.optString("name", "");
        String overview = data.optString("overview", "");
        if (overview.isEmpty()) overview = data.optString("description", "");
        Object rating = data.opt("imdbRating");
        if (rating == null || rating == JSONObject.NULL) rating = data.opt("rating");
        return new Video(data.optString("id"), title, number(data.opt("season")),
                number(data.has("episode") ? data.opt("episode") : data.opt("number")),
                data.optString("released", ""),
                Artwork.sized(data.optString("thumbnail", ""), Artwork.THUMBNAIL),
                overview, MetaItem.rating(rating));
    }

    /** {@code YYYY-MM-DD}, or "". */
    public String airDate() {
        Matcher m = DATE.matcher(released);
        return m.find() ? m.group(1) : "";
    }

    /** {@code today} is an ISO date. Videos without a date count as released. */
    public boolean isReleased(String today) {
        String date = airDate();
        return date.isEmpty() || date.compareTo(today) <= 0;
    }

    private static int number(Object value) {
        if (value == null || value == JSONObject.NULL) return NONE;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return NONE;
        }
    }
}
