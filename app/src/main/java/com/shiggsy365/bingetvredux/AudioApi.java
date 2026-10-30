package com.shiggsy365.bingetvredux;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The web page's radio and podcast endpoints, for the profile picked at the top (see
 * WebServer for the rest). The settings themselves (country, sync server, Podcast Index key)
 * go through /api/profiles/update like the profile's others.
 *
 *   GET  /api/radio?id=                  the profile's favourite stations
 *   GET  /api/radio/search?q=            stations by name, from radio-browser.info
 *   POST /api/radio/favourites           {id, favourites: [...]} — the whole list, in order
 *   GET  /api/podcasts?id=               the profile's podcasts, and how its sync stands
 *   GET  /api/podcasts/search?id=&q=     podcasts, from Podcast Index or Apple's directory
 *   POST /api/podcasts/add               {id, feedUrl} — subscribes (reads the feed first)
 *   POST /api/podcasts/remove            {id, feedUrl}
 *   POST /api/podcasts/sync              {id} — syncs with the profile's gPodder server now
 */
final class AudioApi {
    private final Context mContext;

    AudioApi(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Handles {@code uri}, or returns null if it isn't one of these endpoints. */
    Object route(String uri, Map<String, String> params, JSONObject body) throws Exception {
        switch (uri) {
            case "/api/radio":
                return radio(profile(params.get("id")));
            case "/api/radio/search": {
                String q = params.getOrDefault("q", "").trim();
                if (q.length() < 2) throw new IllegalArgumentException("Type at least two letters");
                return new JSONObject().put("stations", items(Radio.search(q)));
            }
            case "/api/radio/favourites": {
                String id = profile(body.optString("id"));
                JSONArray list = body.optJSONArray("favourites");
                Radio.saveFavourites(mContext, id, Radio.favouritesOf(list == null ? "[]" : list.toString()));
                return radio(id);
            }
            case "/api/podcasts":
                return podcasts(profile(params.get("id")));
            case "/api/podcasts/search": {
                String id = profile(params.get("id"));
                String q = params.getOrDefault("q", "").trim();
                if (q.length() < 2) throw new IllegalArgumentException("Type at least two letters");
                JSONArray out = new JSONArray();
                for (Podcasts.Podcast p : Podcasts.search(mContext, id, q)) out.put(p.toJson());
                return new JSONObject().put("podcasts", out);
            }
            case "/api/podcasts/add": {
                String id = profile(body.optString("id"));
                String url = body.optString("feedUrl").trim();
                if (!url.matches("(?i)https?://.+")) throw new IllegalArgumentException("Enter the podcast's feed URL (http… or https…)");
                Podcasts.Podcast podcast;
                try {
                    podcast = Podcasts.feed(url, false).podcast;
                } catch (IOException e) {
                    throw new IllegalArgumentException("That feed couldn't be read: " + e.getMessage());
                }
                List<Podcasts.Podcast> list = Podcasts.subscriptions(mContext, id);
                if (list.stream().noneMatch(p -> p.feedUrl.equals(url))) {
                    list.add(0, podcast);
                    Podcasts.saveSubscriptions(mContext, id, list);
                    PodcastSync.subscriptionChanged(mContext, id, url, true);
                }
                return podcasts(id);
            }
            case "/api/podcasts/remove": {
                String id = profile(body.optString("id"));
                String url = body.optString("feedUrl");
                List<Podcasts.Podcast> list = Podcasts.subscriptions(mContext, id);
                if (list.removeIf(p -> p.feedUrl.equals(url))) {
                    Podcasts.saveSubscriptions(mContext, id, list);
                    PodcastSync.subscriptionChanged(mContext, id, url, false);
                }
                return podcasts(id);
            }
            case "/api/podcasts/sync": {
                String id = profile(body.optString("id"));
                try {
                    String message = PodcastSync.sync(mContext, id);
                    return podcasts(id).put("message", message);
                } catch (IOException e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
            }
            default:
                return null;
        }
    }

    /** The profile asked about, or the one in use on the TV. */
    private String profile(String id) {
        if (id == null || id.isEmpty()) return Profiles.current(mContext).id;
        if (Profiles.get(mContext, id) == null) throw new IllegalArgumentException("No such profile");
        return id;
    }

    private JSONObject radio(String id) throws JSONException {
        return new JSONObject().put("favourites", items(Radio.favouritesOf(
                Settings.data(mContext, id, Settings.RADIO_FAVOURITES))));
    }

    private JSONObject podcasts(String id) throws JSONException {
        JSONArray list = new JSONArray();
        for (Podcasts.Podcast p : Podcasts.subscriptions(mContext, id)) list.put(p.toJson());
        return new JSONObject().put("podcasts", list)
                .put("syncing", PodcastSync.configured(mContext, id))
                .put("sync", new JSONArray(PodcastSync.pendingSummary(mContext, id)));
    }

    private static JSONArray items(List<AudioPlayer.Item> list) throws JSONException {
        JSONArray out = new JSONArray();
        for (AudioPlayer.Item item : list) out.put(item.toJson());
        return out;
    }
}
