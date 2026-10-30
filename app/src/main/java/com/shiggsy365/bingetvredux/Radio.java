package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.shiggsy365.bingetvredux.stremio.Http;
import com.shiggsy365.bingetvredux.stremio.MetaItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internet radio from radio-browser.info, a free community directory (no account): the top
 * stations in the profile's country and worldwide, by genre, and by name. Each profile keeps
 * its favourite stations (changed on the TV or the web page, and in backups).
 */
final class Radio {
    private static final String TAG = "Radio";
    static final String PREFIX = "radio:";
    private static final int ROW_SIZE = 40;
    private static final String[] FALLBACK_SERVERS = {
            "de1.api.radio-browser.info", "fi1.api.radio-browser.info", "de2.api.radio-browser.info"};
    /** The genre rows on the Radio page. */
    static final String[] GENRES = {"pop", "rock", "news", "talk", "dance", "jazz", "classical",
            "chillout", "country", "hiphop"};
    private static List<String> sServers;
    // Stations seen this run, by id, so a card can be played.
    private static final Map<String, AudioPlayer.Item> sKnown = new ConcurrentHashMap<>();
    private static final Background.Tasks sClicks = new Background.Tasks(1);

    private Radio() {}

    // ---- The directory

    /** The directory's servers, in a random order (as it asks), found once per run. */
    private static synchronized List<String> servers() {
        if (sServers != null) return sServers;
        List<String> names = new ArrayList<>();
        try {
            Http.Response r = Http.get("https://all.api.radio-browser.info/json/servers")
                    .timeout(6_000).send();
            JSONArray list = new JSONArray(r.text());
            for (int i = 0; i < list.length(); i++) {
                String name = list.getJSONObject(i).optString("name");
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Server list: " + e.getMessage());
        }
        if (names.isEmpty()) names.addAll(Arrays.asList(FALLBACK_SERVERS));
        Collections.shuffle(names);
        sServers = names;
        return names;
    }

    /** {@code path} from the first server that answers. */
    private static JSONArray get(String path) throws IOException {
        IOException last = null;
        for (String server : servers()) {
            try {
                Http.Response r = Http.get("https://" + server + path).timeout(8_000).send();
                if (r.ok()) return new JSONArray(r.text());
                last = new IOException("radio-browser " + r.status);
            } catch (IOException e) {
                last = e;
            } catch (JSONException e) {
                last = new IOException(e.getMessage());
            }
        }
        throw last != null ? last : new IOException("No radio directory");
    }

    private static List<AudioPlayer.Item> stations(String path) throws IOException {
        List<AudioPlayer.Item> out = new ArrayList<>();
        JSONArray list = get(path);
        for (int i = 0; i < list.length(); i++) {
            AudioPlayer.Item item = station(list.optJSONObject(i));
            if (item != null) out.add(item);
        }
        return out;
    }

    private static AudioPlayer.Item station(JSONObject s) {
        if (s == null) return null;
        String id = s.optString("stationuuid");
        String url = s.optString("url_resolved");
        if (url.isEmpty()) url = s.optString("url");
        String name = s.optString("name").trim();
        if (id.isEmpty() || url.isEmpty() || name.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        String country = s.optString("countrycode");
        if (!country.isEmpty()) parts.add(country);
        List<String> tags = new ArrayList<>();
        for (String t : s.optString("tags").split(",")) {
            t = t.trim();
            if (!t.isEmpty() && tags.size() < 3) tags.add(t);
        }
        if (!tags.isEmpty()) parts.add(String.join(", ", tags));
        String codec = s.optString("codec");
        int bitrate = s.optInt("bitrate", 0);
        if (!codec.isEmpty() && !codec.equalsIgnoreCase("unknown")) {
            parts.add(codec + (bitrate > 0 ? " " + bitrate + " kbps" : ""));
        }
        AudioPlayer.Item item = new AudioPlayer.Item(AudioPlayer.RADIO, id, name,
                String.join("  ·  ", parts), s.optString("favicon"), url, "", 0, 0);
        sKnown.put(id, item);
        return item;
    }

    private static String common() {
        return "hidebroken=true&order=clickcount&reverse=true&limit=" + ROW_SIZE;
    }

    /** The most listened-to stations in a country (two letters). */
    static List<AudioPlayer.Item> top(String country) throws IOException {
        return stations("/json/stations/search?countrycode=" + Uri.encode(country) + "&" + common());
    }

    static List<AudioPlayer.Item> topWorldwide() throws IOException {
        return stations("/json/stations/topclick/" + ROW_SIZE + "?hidebroken=true");
    }

    static List<AudioPlayer.Item> genre(String tag, String country) throws IOException {
        // In the country first; worldwide if it has too few.
        List<AudioPlayer.Item> local = stations("/json/stations/search?tag=" + Uri.encode(tag)
                + "&tagExact=true&countrycode=" + Uri.encode(country) + "&" + common());
        if (local.size() >= 8) return local;
        return stations("/json/stations/search?tag=" + Uri.encode(tag) + "&tagExact=true&" + common());
    }

    static List<AudioPlayer.Item> search(String name) throws IOException {
        return stations("/json/stations/search?name=" + Uri.encode(name) + "&" + common());
    }

    /** Counts a play, as the directory asks (it ranks stations by it). */
    static void clicked(String id) {
        sClicks.execute(() -> {
            try {
                Http.get("https://" + servers().get(0) + "/json/url/" + Uri.encode(id))
                        .timeout(8_000).send();
            } catch (IOException ignored) {
                // Only a count.
            }
        });
    }

    /** The profile's country for "Popular in": its setting, else the device's. */
    static String country(Context context) {
        String c = Settings.string(context, Settings.RADIO_COUNTRY).trim().toUpperCase(Locale.ROOT);
        if (c.length() == 2) return c;
        c = Locale.getDefault().getCountry().toUpperCase(Locale.ROOT);
        return c.length() == 2 ? c : "GB";
    }

    static String countryName(String code) {
        String name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        return name.isEmpty() ? code : name;
    }

    // ---- Cards

    static MetaItem toMeta(AudioPlayer.Item s) {
        try {
            MetaItem item = MetaItem.preview(new JSONObject().put("id", PREFIX + s.id)
                    .put("type", "radio").put("name", s.title).put("poster", s.artwork)
                    .put("logo", "").put("posterShape", "square"), null);
            // Country, genres and quality once, under the name.
            return item == null ? null : item.forRow(s.subtitle, "");
        } catch (JSONException e) {
            return null;
        }
    }

    static List<MetaItem> toMetas(List<AudioPlayer.Item> stations) {
        List<MetaItem> out = new ArrayList<>();
        for (AudioPlayer.Item s : stations) {
            MetaItem m = toMeta(s);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** The station behind a card, or null. */
    static AudioPlayer.Item station(Context context, String metaId) {
        String id = metaId.substring(PREFIX.length());
        AudioPlayer.Item known = sKnown.get(id);
        if (known != null) return known;
        for (AudioPlayer.Item f : favourites(context)) if (f.id.equals(id)) return f;
        return null;
    }

    // ---- Favourites

    static List<AudioPlayer.Item> favourites(Context context) {
        return favouritesOf(Settings.data(context, Settings.RADIO_FAVOURITES));
    }

    static List<AudioPlayer.Item> favouritesOf(String json) {
        List<AudioPlayer.Item> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                AudioPlayer.Item item = AudioPlayer.Item.fromJson(list.getJSONObject(i));
                if (!item.id.isEmpty() && !item.url.isEmpty()) out.add(item);
            }
        } catch (JSONException e) {
            // None yet.
        }
        return out;
    }

    static boolean isFavourite(Context context, String id) {
        for (AudioPlayer.Item f : favourites(context)) if (f.id.equals(id)) return true;
        return false;
    }

    /** Adds the station at the start, or removes it; returns whether it's now a favourite. */
    static boolean toggleFavourite(Context context, AudioPlayer.Item station) {
        List<AudioPlayer.Item> list = favourites(context);
        boolean removed = list.removeIf(f -> f.id.equals(station.id));
        if (!removed) list.add(0, station);
        saveFavourites(context, Profiles.current(context).id, list);
        return !removed;
    }

    static void saveFavourites(Context context, String profileId, List<AudioPlayer.Item> list) {
        JSONArray out = new JSONArray();
        try {
            for (AudioPlayer.Item f : list) out.put(f.toJson());
        } catch (JSONException e) {
            return;
        }
        Settings.putData(context, profileId, Settings.RADIO_FAVOURITES, out.toString());
    }
}
