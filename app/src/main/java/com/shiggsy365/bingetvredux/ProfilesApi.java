package com.shiggsy365.bingetvredux;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * The web page's profile endpoints (see WebServer for the rest):
 *
 *   GET  /api/profiles                   profiles, which one the TV is using, and each one's
 *                                        settings
 *   POST /api/profiles                   {name} — adds a profile
 *   POST /api/profiles/update            {id, name?, settings?: {...}}
 *   POST /api/profiles/delete            {id} — with its history and settings
 *   POST /api/mdblist/test               {id} — checks the profile's MDBList key
 *   POST /api/mdblist/sync               {} — syncs the TV's current profile with MDBList now
 */
final class ProfilesApi {
    private final Context mContext;

    ProfilesApi(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Handles {@code uri}, or returns null if it isn't one of these endpoints. */
    Object route(String uri, Map<String, String> params, JSONObject body) throws Exception {
        switch (uri) {
            case "/api/profiles":
                if (body != null) Profiles.add(mContext, body.optString("name"));
                return profiles();
            case "/api/profiles/update": {
                String id = body.getString("id");
                if (Profiles.get(mContext, id) == null) throw new IllegalArgumentException("No such profile");
                if (body.has("name")) Profiles.rename(mContext, id, body.getString("name"));
                JSONObject settings = body.optJSONObject("settings");
                if (settings != null) Settings.apply(mContext, id, settings);
                return profiles();
            }
            case "/api/profiles/delete":
                Profiles.remove(mContext, body.getString("id"));
                return profiles();
            case "/api/mdblist/test": {
                String key = settingsOf(body.getString("id")).optString(Settings.MDBLIST_KEY);
                if (key.isEmpty()) throw new IllegalArgumentException("Enter the API key first");
                try {
                    return new JSONObject().put("message", Mdblist.test(key));
                } catch (Mdblist.MdblistException e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
            }
            case "/api/mdblist/sync":
                try {
                    return new JSONObject().put("message", Mdblist.sync(mContext, true))
                            .put("profile", Profiles.current(mContext).name);
                } catch (Mdblist.MdblistException e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
            default:
                return null;
        }
    }

    private JSONObject settingsOf(String id) throws JSONException {
        if (Profiles.get(mContext, id) == null) throw new IllegalArgumentException("No such profile");
        return Settings.toJson(mContext, id);
    }

    private JSONObject profiles() throws JSONException {
        JSONArray list = new JSONArray();
        String current = Profiles.current(mContext).id;
        for (Profiles.Profile p : Profiles.all(mContext)) {
            list.put(new JSONObject().put("id", p.id).put("name", p.name)
                    .put("current", p.id.equals(current))
                    .put("settings", Settings.toJson(mContext, p.id)));
        }
        return new JSONObject().put("profiles", list);
    }
}
