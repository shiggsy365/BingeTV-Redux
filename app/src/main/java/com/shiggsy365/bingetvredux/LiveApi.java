package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The web page's live TV endpoints (see WebServer for the list): the Xtream account and other
 * panels, groups and channels, merges, logos and guide sources. Keeps short-lived copies of
 * what the panels list, so browsing the page doesn't ask them every time.
 */
final class LiveApi {
    private static final String TAG = "LiveApi";
    private static final long STREAM_CACHE_MS = 10 * 60 * 1000;
    private static final int GUIDE_SEARCH_LIMIT = 50;

    private final Context mContext;
    // Category id -> streams, so browsing groups doesn't refetch from the panel every time.
    private final Map<String, List<XtreamClient.Stream>> mStreamCache = new HashMap<>();
    private final Map<String, Long> mStreamCacheTime = new HashMap<>();
    // Source index -> its categories.
    private final Map<Integer, List<XtreamClient.Category>> mCategories = new HashMap<>();

    LiveApi(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Handles a live TV endpoint; null if {@code uri} isn't one. */
    Object route(String uri, Map<String, String> params, JSONObject body)
            throws Exception {
        switch (uri) {
            case "/api/state":
                return state();
            case "/api/settings":
                return saveSettings(require(body));
            case "/api/categories":
                return categories(sourceParam(params));
            case "/api/sources":
                return addSource(require(body));
            case "/api/sources/update":
                return updateSource(require(body));
            case "/api/sources/delete":
                ChannelConfig.edit(mContext, c -> c.removeSource(require(body).getInt("index")));
                clearSourceCaches();
                return state();
            case "/api/groups":
                return addGroup(require(body));
            case "/api/groups/update":
                return updateGroup(require(body));
            case "/api/groups/delete":
                ChannelConfig.edit(mContext, c -> c.removeGroup(require(body).getString("id")));
                return state();
            case "/api/groups/order":
                ChannelConfig.edit(mContext, c -> c.setChannelOrder(require(body).getString("id"),
                        require(body).getJSONArray("order")));
                return new JSONObject().put("ok", true);
            case "/api/channels":
                return body == null ? channels(params.get("group")) : editChannels(body);
            case "/api/channels/merge":
                return mergeChannels(require(body));
            case "/api/channels/split":
                ChannelConfig.edit(mContext, c -> c.splitStream(require(body).getInt("channel"),
                        require(body).getInt("stream")));
                return new JSONObject().put("ok", true);
            case "/api/channels/order": {
                JSONArray order = require(body).getJSONArray("order");
                List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < order.length(); i++) ids.add(order.getInt(i));
                ChannelConfig.edit(mContext, c -> c.orderStreams(body.getInt("channel"), ids));
                return new JSONObject().put("ok", true);
            }
            case "/api/channels/rename":
                ChannelConfig.edit(mContext, c -> c.renameChannel(require(body).getInt("channel"),
                        body.optString("name", "")));
                return new JSONObject().put("ok", true);
            case "/api/channels/renamestream":
                ChannelConfig.edit(mContext, c -> c.renameStream(require(body).getInt("channel"),
                        body.getInt("stream"), body.optString("name", "")));
                return new JSONObject().put("ok", true);
            case "/api/merges/suggest":
                return suggestMerges();
            case "/api/merges/apply":
                return applyMerges(require(body));
            case "/api/streams/search":
                return searchStreams(params.get("q"), sourceParam(params));
            case "/api/channels/addstream": {
                int channel = require(body).getInt("channel");
                int stream = body.getInt("stream");
                if (stream == channel) throw new IllegalArgumentException("That's this channel's own stream");
                Map<Integer, String> names = new HashMap<>();
                names.put(stream, body.optString("name", "Stream " + stream));
                ChannelConfig.edit(mContext, c -> c.mergeChannel(stream, channel, names));
                return new JSONObject().put("ok", true);
            }
            case "/api/guides":
                return addGuide(require(body));
            case "/api/guides/delete":
                return deleteGuide(require(body).getString("id"));
            case "/api/guides/update":
                return updateGuide(require(body));
            case "/api/guides/refresh":
                return refreshGuide(require(body).getString("id"));
            case "/api/guides/channels":
                return searchGuide(params.get("source"), params.get("q"));
            case "/api/logos":
                return setLogos(require(body));
            case "/api/connections":
                return connections();
            case "/api/sync":
                ChannelSyncWorker.syncNow(mContext);
                return new JSONObject().put("ok", true);
            default:
                return null;
        }
    }

    // ---- State and settings ----

    JSONObject state() throws JSONException {
        SharedPreferences prefs = XtreamClient.prefs(mContext);
        ChannelConfig config = ChannelConfig.load(mContext);
        JSONObject settings = new JSONObject()
                .put("name", Sources.name(mContext, 0))
                .put("server", prefs.getString(XtreamClient.PREF_SERVER, ""))
                .put("username", prefs.getString(XtreamClient.PREF_USERNAME, ""))
                .put("hasPassword", !prefs.getString(XtreamClient.PREF_PASSWORD, "").isEmpty())
                .put("useHls", prefs.getBoolean(XtreamClient.PREF_USE_HLS, false))
                .put("prefixes", config.prefixes())
                .put("suffixes", config.suffixes());
        return new JSONObject()
                .put("settings", settings)
                .put("groups", config.groups())
                .put("sources", sourcesJson(config))
                .put("guideSources", guideSourcesWithCounts(config))
                .put("sync", new JSONObject()
                        .put("running", GuideSync.sSyncRunning)
                        .put("status", GuideSync.sSyncStatus)
                        .put("last", prefs.getLong(XtreamClient.PREF_LAST_SYNC, 0)));
    }

    /** Every guide source with its channel count, whether it's off and when sync last read it. */
    private JSONArray guideSourcesWithCounts(ChannelConfig config) throws JSONException {
        JSONArray result = guideSourcesList(config);
        SharedPreferences times = GuideSync.guideTimes(mContext);
        for (int i = 0; i < result.length(); i++) {
            JSONObject s = result.getJSONObject(i);
            String id = s.getString("id");
            s.put("disabled", config.guideDisabled(id))
                    .put("refreshed", times.getLong(id + ".ok", 0))
                    .put("failed", times.getLong(id + ".failed", 0));
        }
        return result;
    }

    private JSONArray guideSourcesList(ChannelConfig config) throws JSONException {
        JSONArray result = new JSONArray();
        result.put(new JSONObject().put("id", ChannelConfig.XTREAM_GUIDE)
                .put("name", Sources.name(mContext, 0) + " panel guide").put("builtIn", true)
                .put("channels", cachedGuideCount(ChannelConfig.XTREAM_GUIDE)));
        JSONArray panels = config.sources();
        for (int i = 0; i < panels.length(); i++) {
            JSONObject panel = panels.getJSONObject(i);
            String id = Sources.guideId(panel.optInt("index"));
            result.put(new JSONObject().put("id", id)
                    .put("name", panel.optString("name") + " panel guide").put("builtIn", true)
                    .put("channels", cachedGuideCount(id)));
        }
        JSONArray sources = config.guideSources();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = new JSONObject(sources.getJSONObject(i).toString());
            s.put("channels", cachedGuideCount(s.getString("id")));
            result.put(s);
        }
        return result;
    }

    private JSONObject saveSettings(JSONObject body) throws Exception {
        SharedPreferences.Editor editor = XtreamClient.prefs(mContext).edit();
        if (body.has("name")) {
            editor.putString(XtreamClient.PREF_NAME, body.getString("name").trim());
        }
        if (body.has("server")) {
            editor.putString(XtreamClient.PREF_SERVER, body.getString("server").trim());
        }
        if (body.has("username")) {
            editor.putString(XtreamClient.PREF_USERNAME, body.getString("username").trim());
        }
        String password = body.optString("password", "");
        if (!password.isEmpty()) editor.putString(XtreamClient.PREF_PASSWORD, password.trim());
        if (body.has("useHls")) editor.putBoolean(XtreamClient.PREF_USE_HLS, body.getBoolean("useHls"));
        editor.commit();
        ChannelConfig.edit(mContext, c -> {
            c.setStripLists(body.optString("prefixes", c.prefixes()),
                    body.optString("suffixes", c.suffixes()));
        });
        synchronized (this) {
            mCategories.clear();
            mStreamCache.clear();
        }
        XtreamClient client = XtreamClient.fromPrefs(mContext);
        String accountError = client == null ? "Account details incomplete" : client.checkAccount();
        return state().put("accountError", accountError == null ? JSONObject.NULL : accountError);
    }

    // ---- Groups ----

    /** A source's categories; "key" is what a group stores (see Sources.categoryKey). */
    private JSONArray categories(int source) throws Exception {
        List<XtreamClient.Category> categories;
        synchronized (this) {
            categories = mCategories.get(source);
        }
        if (categories == null) {
            XtreamClient client = Sources.client(mContext, source);
            if (client == null) throw new IllegalArgumentException("That source isn't set up");
            categories = client.getLiveCategories();
            synchronized (this) {
                mCategories.put(source, categories);
            }
        }
        JSONArray result = new JSONArray();
        for (XtreamClient.Category c : categories) {
            result.put(new JSONObject().put("id", c.id).put("name", c.name)
                    .put("key", Sources.categoryKey(source, c.id)));
        }
        return result;
    }

    private static int sourceParam(Map<String, String> params) {
        String source = params.get("source");
        return TextUtils.isEmpty(source) ? 0 : Integer.parseInt(source);
    }

    // ---- Sources ----

    private JSONArray sourcesJson(ChannelConfig config) throws JSONException {
        SharedPreferences prefs = XtreamClient.prefs(mContext);
        JSONArray result = new JSONArray();
        result.put(new JSONObject().put("index", 0).put("name", Sources.name(mContext, 0))
                .put("server", prefs.getString(XtreamClient.PREF_SERVER, ""))
                .put("username", prefs.getString(XtreamClient.PREF_USERNAME, ""))
                .put("useHls", prefs.getBoolean(XtreamClient.PREF_USE_HLS, false))
                .put("main", true));
        JSONArray sources = config.sources();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject src = sources.getJSONObject(i);
            result.put(new JSONObject().put("index", src.optInt("index"))
                    .put("name", src.optString("name"))
                    .put("server", src.optString("server"))
                    .put("username", src.optString("username"))
                    .put("useHls", src.optBoolean("useHls"))
                    .put("hasPassword", !src.optString("password").isEmpty()));
        }
        return result;
    }

    /** Each Xtream account's connections in use and its limit, asked of the panels at once. */
    private JSONObject connections() throws Exception {
        JSONArray sources = sourcesJson(ChannelConfig.load(mContext));
        List<java.util.concurrent.Callable<int[]>> tasks = new ArrayList<>();
        for (int i = 0; i < sources.length(); i++) {
            int index = sources.getJSONObject(i).getInt("index");
            tasks.add(() -> {
                XtreamClient client = Sources.client(mContext, index);
                return client == null ? null : client.connections();
            });
        }
        List<com.shiggsy365.bingetvredux.stremio.Parallel.Result<int[]>> results =
                com.shiggsy365.bingetvredux.stremio.Parallel.run(tasks, 10_000);
        JSONArray out = new JSONArray();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject src = sources.getJSONObject(i);
            int[] cons = results.get(i).value;
            JSONObject o = new JSONObject().put("index", src.getInt("index"))
                    .put("name", src.optString("name"));
            if (cons != null) o.put("active", cons[0]).put("max", cons[1]);
            out.put(o);
        }
        return new JSONObject().put("connections", out);
    }

    private JSONObject addSource(JSONObject body) throws Exception {
        String server = body.optString("server", "").trim();
        String username = body.optString("username", "").trim();
        String password = body.optString("password", "").trim();
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("Server, username and password are required");
        }
        boolean useHls = body.optBoolean("useHls", false);
        String error = new XtreamClient(server, username, password, useHls).checkAccount();
        if (error != null) throw new IllegalArgumentException(error);
        ChannelConfig.edit(mContext, c -> c.addSource(body.optString("name", "").trim(), server,
                username, password, useHls));
        return state();
    }

    /** Updates a source's details; an empty password keeps the current one. */
    private JSONObject updateSource(JSONObject body) throws Exception {
        int index = body.getInt("index");
        JSONObject current = ChannelConfig.load(mContext).source(index);
        if (current == null) throw new IllegalArgumentException("No such source");
        String server = body.optString("server", current.optString("server")).trim();
        String username = body.optString("username", current.optString("username")).trim();
        String password = body.optString("password", "").trim();
        if (password.isEmpty()) password = current.optString("password");
        boolean useHls = body.optBoolean("useHls", current.optBoolean("useHls"));
        String error = new XtreamClient(server, username, password, useHls).checkAccount();
        if (error != null) throw new IllegalArgumentException(error);
        String finalPassword = password;
        ChannelConfig.edit(mContext, c -> {
            JSONObject src = c.source(index);
            src.put("name", body.optString("name", src.optString("name")).trim());
            src.put("server", server).put("username", username).put("password", finalPassword)
                    .put("useHls", useHls);
        });
        clearSourceCaches();
        return state();
    }

    private synchronized void clearSourceCaches() {
        mCategories.clear();
        mStreamCache.clear();
        mStreamCacheTime.clear();
        mAllStreams.clear();
    }

    private JSONObject addGroup(JSONObject body) throws Exception {
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Group name is required");
        String categoryId = body.optString("categoryId", "");
        ChannelConfig.edit(mContext, c -> {
            if (categoryId.isEmpty()) c.addCustomGroup(name);
            else c.addXtreamGroup(categoryId, name);
        });
        return state();
    }

    private JSONObject updateGroup(JSONObject body) throws Exception {
        String id = body.getString("id");
        ChannelConfig.edit(mContext, c -> {
            JSONObject group = c.group(id);
            if (group == null) throw new IllegalArgumentException("No such group");
            if (body.has("name") && !body.getString("name").trim().isEmpty()) {
                group.put("name", body.getString("name").trim());
            }
            if (body.has("enabled")) group.put("enabled", body.getBoolean("enabled"));
            if (body.has("move")) c.moveGroup(id, body.getInt("move"));
        });
        return state();
    }

    // ---- Channels ----

    private JSONArray channels(String groupId) throws Exception {
        if (TextUtils.isEmpty(groupId)) throw new IllegalArgumentException("group is required");
        ChannelConfig config = ChannelConfig.load(mContext);
        Map<String, String> guideNames = new HashMap<>();
        Map<Integer, String> sourceNames = Sources.names(mContext);
        JSONArray result = new JSONArray();
        for (ChannelConfig.Entry e : config.buildLineup(this::cachedStreams, groupId)) {
            String guideName = null;
            if (e.guideChannel != null) {
                guideName = guideChannelName(guideNames, e.guideSource, e.guideChannel);
            }
            result.put(new JSONObject()
                    .put("streamId", e.stream.streamId)
                    .put("name", e.stream.name)
                    .put("displayName", e.displayName)
                    .put("defaultName", e.defaultName)
                    .put("icon", e.stream.iconUrl == null ? "" : e.stream.iconUrl)
                    .put("logoSource", e.logoSource)
                    .put("logo", ChannelConfig.LOGO_CUSTOM.equals(e.logoSource)
                            ? "/logos/" + e.stream.streamId + ".png?v=" + e.logoVersion
                            : e.logoUrl == null ? "" : e.logoUrl)
                    .put("category", e.stream.categoryId)
                    .put("hidden", e.hidden)
                    .put("moved", e.moved)
                    .put("guideSource", e.guideSource)
                    .put("guideChannel", e.guideChannel == null ? "" : e.guideChannel)
                    .put("guideName", guideName == null ? JSONObject.NULL : guideName)
                    .put("epgOffset", e.epgOffsetMin)
                    .put("catchup", catchupDays(e.streams))
                    .put("streams", streamsJson(e, sourceNames)));
        }
        return result;
    }

    /**
     * Applies one edit to a set of channels in a group: hide/show, move to another group,
     * remove from the group they were moved to (back to their own category), or link a guide
     * channel ({source, channel}, or null to restore the panel default).
     */
    private JSONObject editChannels(JSONObject body) throws Exception {
        JSONArray ids = body.getJSONArray("streamIds");
        String groupId = body.getString("group");
        // Moving needs each stream's details; take them from the group being edited.
        Map<Integer, XtreamClient.Stream> streams = new HashMap<>();
        if (body.has("moveTo") || body.optBoolean("remove")) {
            for (ChannelConfig.Entry e : ChannelConfig.load(mContext)
                    .buildLineup(this::cachedStreams, groupId)) {
                streams.put(e.stream.streamId, e.stream);
            }
        }
        ChannelConfig.edit(mContext, c -> {
            for (int i = 0; i < ids.length(); i++) {
                int streamId = ids.getInt(i);
                if (body.has("hidden")) c.setHidden(streamId, body.getBoolean("hidden"));
                if (body.has("moveTo")) {
                    XtreamClient.Stream stream = streams.get(streamId);
                    if (stream != null) c.moveChannel(stream, body.getString("moveTo"));
                }
                if (body.optBoolean("remove")) {
                    XtreamClient.Stream stream = streams.get(streamId);
                    if (stream != null) c.moveChannel(stream,
                            ChannelConfig.XTREAM_GROUP_PREFIX + stream.categoryId);
                }
                if (body.has("epgOffset")) c.setEpgOffset(streamId, body.getInt("epgOffset"));
                if (body.has("guide")) {
                    JSONObject guide = body.optJSONObject("guide");
                    if (guide == null) c.linkGuide(streamId, null, null);
                    else c.linkGuide(streamId, guide.getString("source"),
                            guide.optString("channel", ""));
                }
            }
        });
        // A channel showing its guide's logo follows the new guide link.
        if (body.has("guide")) refreshEpgLogos(ids, groupId);
        return new JSONObject().put("ok", true);
    }

    // ---- Channels and streams ----

    private JSONArray streamsJson(ChannelConfig.Entry e, Map<Integer, String> sourceNames)
            throws JSONException {
        JSONArray streams = new JSONArray();
        for (int id : e.streams) {
            String name = e.streamNames.get(id);
            streams.put(new JSONObject().put("id", id)
                    .put("name", name == null ? "Stream " + id : name)
                    .put("source", Sources.name(sourceNames, id))
                    .put("catchup", Catchup.days(mContext, id)));
        }
        return streams;
    }

    /** The most catch-up days of any of the streams, as of the last sync. */
    private int catchupDays(List<Integer> streams) {
        int days = 0;
        for (int id : streams) days = Math.max(days, Catchup.days(mContext, id));
        return days;
    }

    /** Stream id -> display name for every stream of the given entries. */
    private static Map<Integer, String> streamNames(List<ChannelConfig.Entry> entries) {
        Map<Integer, String> names = new HashMap<>();
        for (ChannelConfig.Entry e : entries) names.putAll(e.streamNames);
        return names;
    }

    /** Merges one channel into another in the same group ("merge up" on the web page). */
    private JSONObject mergeChannels(JSONObject body) throws Exception {
        int from = body.getInt("from");
        int into = body.getInt("into");
        Map<Integer, String> names = streamNames(ChannelConfig.load(mContext)
                .buildLineup(this::cachedStreams, body.getString("group")));
        ChannelConfig.edit(mContext, c -> c.mergeChannel(from, into, names));
        return new JSONObject().put("ok", true);
    }

    /**
     * Finds visible channels whose names match once quality tags are ignored (BBC 1 HD, BBC 1
     * 4K, BBC 1 HEVC...). Each suggestion merges the later ones into the first in the lineup.
     */
    private JSONObject suggestMerges() throws Exception {
        ChannelConfig config = ChannelConfig.load(mContext);
        List<ChannelConfig.Entry> lineup = config.buildLineup(this::cachedStreams, null);
        Map<String, List<ChannelConfig.Entry>> byName = new java.util.LinkedHashMap<>();
        for (ChannelConfig.Entry e : lineup) {
            String key = ChannelConfig.matchName(e.displayName);
            if (key.isEmpty()) continue;
            List<ChannelConfig.Entry> list = byName.get(key);
            if (list == null) byName.put(key, list = new ArrayList<>());
            list.add(e);
        }
        JSONArray suggestions = new JSONArray();
        for (List<ChannelConfig.Entry> list : byName.values()) {
            if (list.size() < 2) continue;
            JSONArray channels = new JSONArray();
            for (ChannelConfig.Entry e : list) {
                JSONObject group = config.group(e.groupId);
                channels.put(new JSONObject().put("id", e.stream.streamId)
                        .put("name", e.displayName)
                        .put("original", e.stream.name)
                        .put("group", group == null ? "" : group.optString("name"))
                        .put("streams", e.streams.size()));
            }
            suggestions.put(new JSONObject().put("into", list.get(0).stream.streamId)
                    .put("channels", channels));
        }
        return new JSONObject().put("suggestions", suggestions);
    }

    private JSONObject applyMerges(JSONObject body) throws Exception {
        JSONArray merges = body.getJSONArray("merges");
        ChannelConfig config = ChannelConfig.load(mContext);
        Map<Integer, String> names = streamNames(config.buildLineup(this::cachedStreams, null));
        // The group being edited may include hidden channels, which the full lineup leaves out.
        if (body.has("group")) {
            names.putAll(streamNames(config.buildLineup(this::cachedStreams,
                    body.getString("group"))));
        }
        int[] merged = {0};
        ChannelConfig.edit(mContext, c -> {
            for (int i = 0; i < merges.length(); i++) {
                JSONObject merge = merges.getJSONObject(i);
                int into = merge.getInt("into");
                JSONArray from = merge.getJSONArray("from");
                for (int j = 0; j < from.length(); j++) {
                    c.mergeChannel(from.getInt(j), into, names);
                    merged[0]++;
                }
            }
        });
        return new JSONObject().put("merged", merged[0]);
    }

    // Every live stream on the panel, for Add stream; slow to fetch, so kept for a while.
    // Source index -> every live stream on it, and when that was fetched.
    private final Map<Integer, List<XtreamClient.Stream>> mAllStreams = new HashMap<>();
    private final Map<Integer, Long> mAllStreamsTime = new HashMap<>();
    private static final long ALL_STREAMS_CACHE_MS = 30 * 60 * 1000;
    private static final int STREAM_SEARCH_LIMIT = 60;

    /** Matches every word of the query against stream names, across the whole panel. */
    private JSONObject searchStreams(String query, int source) throws Exception {
        List<XtreamClient.Stream> all;
        synchronized (this) {
            Long fetched = mAllStreamsTime.get(source);
            all = mAllStreams.get(source);
            if (all == null || fetched == null
                    || System.currentTimeMillis() - fetched > ALL_STREAMS_CACHE_MS) {
                all = Sources.allStreams(mContext, source);
                // Keep one source's list at a time to bound memory.
                mAllStreams.clear();
                mAllStreamsTime.clear();
                mAllStreams.put(source, all);
                mAllStreamsTime.put(source, System.currentTimeMillis());
            }
        }
        Map<String, String> categoryNames = new HashMap<>();
        JSONArray categories = categories(source);
        for (int i = 0; i < categories.length(); i++) {
            JSONObject c = categories.getJSONObject(i);
            categoryNames.put(c.getString("key"), c.getString("name"));
        }
        List<String> words = new ArrayList<>();
        for (String w : (query == null ? "" : query).toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty()) words.add(w);
        }
        JSONArray matches = new JSONArray();
        if (!words.isEmpty()) {
            for (XtreamClient.Stream st : all) {
                String name = st.name.toLowerCase(Locale.ROOT);
                boolean ok = true;
                for (String w : words) {
                    if (!name.contains(w)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                String category = categoryNames.get(st.categoryId);
                matches.put(new JSONObject().put("id", st.streamId).put("name", st.name)
                        .put("category", category == null ? "" : category)
                        .put("catchup", Catchup.days(mContext, st.streamId)));
                if (matches.length() >= STREAM_SEARCH_LIMIT) break;
            }
        }
        return new JSONObject().put("total", all.size()).put("matches", matches);
    }

    // ---- Logos ----

    /** Sets the logo source for channels: "m3u" (the panel's) or "epg" (the linked guide's). */
    private JSONObject setLogos(JSONObject body) throws Exception {
        JSONArray ids = body.getJSONArray("streamIds");
        String groupId = body.getString("group");
        String source = body.getString("source");
        if (ChannelConfig.LOGO_M3U.equals(source)) {
            ChannelConfig.edit(mContext, c -> {
                for (int i = 0; i < ids.length(); i++) {
                    c.setLogo(ids.getInt(i), ChannelConfig.LOGO_M3U, null);
                }
            });
            return new JSONObject().put("updated", ids.length()).put("missing", 0);
        }
        if (!ChannelConfig.LOGO_EPG.equals(source)) {
            throw new IllegalArgumentException("Unknown logo source " + source);
        }
        Map<Integer, String> icons = epgIcons(ids, groupId);
        ChannelConfig.edit(mContext, c -> {
            for (Map.Entry<Integer, String> icon : icons.entrySet()) {
                c.setLogo(icon.getKey(), ChannelConfig.LOGO_EPG, icon.getValue());
            }
        });
        return new JSONObject().put("updated", icons.size())
                .put("missing", ids.length() - icons.size());
    }

    /** Re-reads the guide logo for channels whose logo source is EPG. */
    private void refreshEpgLogos(JSONArray ids, String groupId) throws Exception {
        ChannelConfig config = ChannelConfig.load(mContext);
        JSONArray epgIds = new JSONArray();
        for (int i = 0; i < ids.length(); i++) {
            if (ChannelConfig.LOGO_EPG.equals(config.logoSource(ids.getInt(i)))) {
                epgIds.put(ids.getInt(i));
            }
        }
        if (epgIds.length() == 0) return;
        Map<Integer, String> icons = epgIcons(epgIds, groupId);
        ChannelConfig.edit(mContext, c -> {
            for (int i = 0; i < epgIds.length(); i++) {
                int streamId = epgIds.getInt(i);
                String icon = icons.get(streamId);
                if (icon != null) c.setLogo(streamId, ChannelConfig.LOGO_EPG, icon);
                else c.setLogo(streamId, ChannelConfig.LOGO_M3U, null);
            }
        });
    }

    /** Stream id -> logo URL from each channel's linked guide, for those that have one. */
    private Map<Integer, String> epgIcons(JSONArray ids, String groupId) throws Exception {
        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        for (int i = 0; i < ids.length(); i++) wanted.add(ids.getInt(i));
        Map<Integer, String> icons = new HashMap<>();
        for (ChannelConfig.Entry e : ChannelConfig.load(mContext)
                .buildLineup(this::cachedStreams, groupId)) {
            if (!wanted.contains(e.stream.streamId) || e.guideChannel == null) continue;
            String icon = guideChannelIcon(e.guideSource, e.guideChannel);
            if (icon != null) icons.put(e.stream.streamId, icon);
        }
        return icons;
    }

    private String guideChannelIcon(String source, String channel) {
        JSONArray channels = guideChannels(source);
        if (channels == null) return null;
        for (int i = 0; i < channels.length(); i++) {
            JSONArray ch = channels.optJSONArray(i);
            if (ch != null && channel.equals(ch.optString(0))) {
                String icon = ch.optString(2, "");
                return icon.isEmpty() ? null : icon;
            }
        }
        return null;
    }

    private synchronized List<XtreamClient.Stream> cachedStreams(String categoryId)
            throws IOException, JSONException {
        Long fetched = mStreamCacheTime.get(categoryId);
        List<XtreamClient.Stream> streams = mStreamCache.get(categoryId);
        if (streams == null || fetched == null
                || System.currentTimeMillis() - fetched > STREAM_CACHE_MS) {
            streams = Sources.streams(mContext, categoryId);
            mStreamCache.put(categoryId, streams);
            mStreamCacheTime.put(categoryId, System.currentTimeMillis());
        }
        return streams;
    }

    // ---- Guide sources ----

    private JSONObject addGuide(JSONObject body) throws Exception {
        String url = body.optString("url", "").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Guide URL must start with http:// or https://");
        }
        String[] id = new String[1];
        ChannelConfig.edit(mContext,
                c -> id[0] = c.addGuideSource(body.optString("name", "").trim(), url)
                        .getString("id"));
        refreshGuide(id[0]);
        return state();
    }

    private JSONObject deleteGuide(String id) throws Exception {
        if (Sources.sourceOfGuide(id) >= 0) {
            throw new IllegalArgumentException("The panel guide can't be removed");
        }
        ChannelConfig.edit(mContext, c -> c.removeGuideSource(id));
        //noinspection ResultOfMethodCallIgnored
        guideCacheFile(id).delete();
        GuideSync.guideTimes(mContext).edit().remove(id + ".ok").remove(id + ".failed").apply();
        return state();
    }

    private JSONObject updateGuide(JSONObject body) throws Exception {
        String id = body.getString("id");
        if (ChannelConfig.load(mContext).guideUrl(mContext, id) == null) {
            throw new IllegalArgumentException("No such guide source");
        }
        ChannelConfig.edit(mContext, c -> c.setGuideDisabled(id, body.getBoolean("disabled")));
        return state();
    }

    /** Downloads a guide's channel list and caches it for linking and search. */
    private JSONObject refreshGuide(String id) throws Exception {
        String url = ChannelConfig.load(mContext).guideUrl(mContext, id);
        if (url == null) throw new IllegalArgumentException("No such guide source");
        List<Xmltv.GuideChannel> channels = Xmltv.readChannels(url);
        JSONArray cache = new JSONArray();
        for (Xmltv.GuideChannel ch : channels) {
            cache.put(new JSONArray().put(ch.id).put(ch.name).put(ch.icon == null ? "" : ch.icon));
        }
        try (OutputStream out = new FileOutputStream(guideCacheFile(id))) {
            out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
        }
        synchronized (this) {
            mGuideCache.remove(id);
        }
        return state().put("refreshed", new JSONObject().put("id", id)
                .put("channels", channels.size()));
    }

    // Guide id -> [[channelId, name], ...], loaded from the cache file on first use.
    private final Map<String, JSONArray> mGuideCache = new HashMap<>();

    private synchronized JSONArray guideChannels(String id) {
        JSONArray channels = mGuideCache.get(id);
        if (channels != null) return channels;
        File file = guideCacheFile(id);
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            channels = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
            mGuideCache.put(id, channels);
            return channels;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not read guide cache " + id, e);
            return null;
        }
    }

    private int cachedGuideCount(String id) {
        JSONArray channels = guideChannels(id);
        return channels == null ? -1 : channels.length();
    }

    private String guideChannelName(Map<String, String> memo, String source, String channel) {
        String key = source + "\n" + channel;
        if (memo.containsKey(key)) return memo.get(key);
        String name = null;
        JSONArray channels = guideChannels(source);
        if (channels != null) {
            for (int i = 0; i < channels.length(); i++) {
                JSONArray ch = channels.optJSONArray(i);
                if (ch != null && channel.equals(ch.optString(0))) {
                    name = ch.optString(1);
                    break;
                }
            }
        }
        memo.put(key, name);
        return name;
    }

    /** Matches every word of the query against guide channel names and ids. */
    private JSONObject searchGuide(String source, String query) throws JSONException {
        if (TextUtils.isEmpty(source)) throw new IllegalArgumentException("source is required");
        JSONArray channels = guideChannels(source);
        JSONObject result = new JSONObject().put("loaded", channels != null);
        JSONArray matches = new JSONArray();
        if (channels != null) {
            List<String> words = new ArrayList<>();
            for (String w : (query == null ? "" : query).toLowerCase(Locale.ROOT).split("\\s+")) {
                if (!w.isEmpty()) words.add(w);
            }
            for (int i = 0; i < channels.length() && matches.length() < GUIDE_SEARCH_LIMIT; i++) {
                JSONArray ch = channels.getJSONArray(i);
                String haystack = (ch.optString(0) + " " + ch.optString(1)).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String w : words) {
                    if (!haystack.contains(w)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    matches.put(new JSONObject().put("id", ch.optString(0))
                            .put("name", ch.optString(1)));
                }
            }
        }
        return result.put("matches", matches);
    }

    private File guideCacheFile(String id) {
        return new File(mContext.getFilesDir(), "guide_" + id.replaceAll("[^A-Za-z0-9_-]", "")
                + ".json");
    }

    /** After a restore: everything kept from the panels and guides is read again. */
    synchronized void clearCaches() {
        mCategories.clear();
        mStreamCache.clear();
        mStreamCacheTime.clear();
        mGuideCache.clear();
    }

    // ---- Plumbing ----

    private static JSONObject require(JSONObject body) {
        if (body == null) throw new IllegalArgumentException("Expected a JSON body");
        return body;
    }
}
