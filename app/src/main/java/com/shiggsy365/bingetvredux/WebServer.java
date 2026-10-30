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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Serves the channel manager page (assets/web/index.html) and its JSON API on the local
 * network. The Xtream password is never sent to the browser; the page can only replace it.
 *
 * Every API call but /api/pair needs a paired browser's cookie (see WebAuth).
 *
 * API (all JSON):
 *   POST /api/pair                        {pin} — pairs this browser
 *   GET  /api/state                       settings, groups, guide sources, sync status
 *   POST /api/settings                    account, strip lists, group rows
 *   GET  /api/categories?source=N         a source's live categories (0 = main account)
 *   POST /api/sources                     {name, server, username, password, useHls}
 *   POST /api/sources/update              {index, name?, server?, username?, password?, useHls?}
 *   POST /api/sources/delete              {index}
 *   POST /api/groups                      {categoryId,name} or {name} for a custom group
 *   POST /api/groups/update               {id, name?, enabled?, move?: -1|1}
 *   POST /api/groups/delete               {id}
 *   POST /api/groups/order                {id, order: [streamId, ...]}
 *   GET  /api/channels?group=ID           a group's channels, including hidden ones
 *   POST /api/channels                    {streamIds, group, hidden?, moveTo?, remove?, guide?,
 *                                          epgOffset? (minutes)}
 *   POST /api/channels/merge              {group, from, into} — merge channel "from" into "into"
 *   POST /api/channels/split              {channel, stream}
 *   POST /api/channels/order              {channel, order: [streamId, ...]}
 *   POST /api/channels/rename             {channel, name} — "" restores the panel name
 *   POST /api/channels/renamestream       {channel, stream, name} — "" restores it
 *   GET  /api/merges/suggest              channels with matching names, as merge suggestions
 *   POST /api/merges/apply                {merges: [{into, from: [channelId, ...]}], group?}
 *   GET  /api/streams/search?q=TEXT       any live stream on the panel, by name
 *   POST /api/channels/addstream          {channel, stream, name} — add a stream as a backup
 *   POST /api/guides                      {name, url}
 *   POST /api/guides/delete               {id}
 *   POST /api/guides/update               {id, disabled}
 *   POST /api/guides/refresh              {id} — re-reads the source's channel list
 *   GET  /api/guides/channels?source=ID&q=TEXT
 *   POST /api/logos                       {streamIds, group, source: "m3u"|"epg"}
 *   POST /api/logos/upload?streamId=ID    image bytes — sets a custom logo
 *   GET  /logos/ID.png                    an uploaded logo
 *   POST /api/profiles/avatar?id=ID        image bytes — a profile's picture
 *   GET  /avatars/ID.png                  a profile's picture
 *   GET  /api/backup?password=0|1         zip of settings, config, logos and guide lists
 *   POST /api/restore                     zip bytes from /api/backup; replaces everything
 *   GET  /api/connections                each account's connections in use and limit
 *   POST /api/sync
 *
 * Live TV's endpoints are LiveApi's; Stremio addons and catalogs: see AddonsApi. Profiles and
 * their settings: see ProfilesApi.
 */
public class WebServer extends NanoHTTPD {
    private static final String TAG = "WebServer";
    public static final int PORT = 8090;
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    // Uploaded logos are scaled down to fit this box; Fire TV shows them much smaller.
    private static final int MAX_LOGO_PX = 600;
    private static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;
    private static final int BACKUP_VERSION = 1;
    private static final String BACKUP_APP = "BingeTV Redux";
    private static final String NOT_A_BACKUP = "That file isn't a BingeTV Redux or FyrTuner backup";

    private final Context mContext;
    private final LiveApi mLiveApi;
    private final AddonsApi mAddonsApi;
    private final ProfilesApi mProfilesApi;

    /** Address -> last request time of devices that opened the page (see WebServerService). */
    static final Map<String, Long> sRecentClients = new java.util.concurrent.ConcurrentHashMap<>();

    public WebServer(Context context) {
        super(PORT);
        mContext = context.getApplicationContext();
        mLiveApi = new LiveApi(mContext);
        mAddonsApi = new AddonsApi(mContext);
        mProfilesApi = new ProfilesApi(mContext);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String client = session.getRemoteIpAddress();
        if (client != null) sRecentClients.put(client, System.currentTimeMillis());
        try {
            if (!uri.startsWith("/api/") && !uri.startsWith("/logos/") && !uri.startsWith("/avatars/")) {
                return serveAsset(uri);
            }
            if ("/api/pair".equals(uri) && Method.POST.equals(session.getMethod())) {
                return pair(readBody(session));
            }
            if (!WebAuth.isPaired(mContext, session.getCookies().read(WebAuth.COOKIE))) {
                return error(Response.Status.UNAUTHORIZED, "Enter the PIN shown on the TV");
            }
            if (uri.startsWith("/logos/")) return serveLogo(uri);
            if (uri.startsWith("/avatars/")) return serveAvatar(uri);
            if ("/api/profiles/avatar".equals(uri) && Method.POST.equals(session.getMethod())) {
                return json(Response.Status.OK, uploadAvatar(session));
            }
            if ("/api/logos/upload".equals(uri) && Method.POST.equals(session.getMethod())) {
                return json(Response.Status.OK, uploadLogo(session));
            }
            if ("/api/backup".equals(uri)) {
                return backup(!"0".equals(session.getParms().get("password")));
            }
            if ("/api/restore".equals(uri) && Method.POST.equals(session.getMethod())) {
                return json(Response.Status.OK, restore(session));
            }
            JSONObject body = Method.POST.equals(session.getMethod()) ? readBody(session) : null;
            Object result = mLiveApi.route(uri, session.getParms(), body);
            if (result == null) result = mAddonsApi.route(uri, session.getParms(), body);
            if (result == null) result = mProfilesApi.route(uri, session.getParms(), body);
            if (result == null) throw new IllegalArgumentException("Unknown endpoint " + uri);
            return json(Response.Status.OK, result);
        } catch (IllegalArgumentException
                 | com.shiggsy365.bingetvredux.stremio.StremioException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Request failed: " + uri, e);
            return error(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    /** Saves an uploaded image as a channel's custom logo, scaled down to MAX_LOGO_PX. */
    private JSONObject uploadLogo(IHTTPSession session) throws Exception {
        String id = session.getParms().get("streamId");
        if (TextUtils.isEmpty(id)) throw new IllegalArgumentException("streamId is required");
        int streamId = Integer.parseInt(id);
        byte[] data = readBytes(session, MAX_UPLOAD_BYTES);
        android.graphics.Bitmap bitmap =
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) throw new IllegalArgumentException("That file isn't an image");
        float scale = Math.min(1f, MAX_LOGO_PX / (float) Math.max(bitmap.getWidth(),
                bitmap.getHeight()));
        if (scale < 1f) {
            bitmap = android.graphics.Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        }
        File file = ChannelConfig.customLogoFile(mContext, streamId);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        ChannelConfig.edit(mContext, c -> c.setLogo(streamId, ChannelConfig.LOGO_CUSTOM, null));
        return new JSONObject().put("ok", true).put("width", bitmap.getWidth())
                .put("height", bitmap.getHeight());
    }

    /** A profile picture: centre-cropped to a square, AVATAR_PX across. */
    private JSONObject uploadAvatar(IHTTPSession session) throws Exception {
        String id = session.getParms().get("id");
        if (TextUtils.isEmpty(id) || Profiles.get(mContext, id) == null) {
            throw new IllegalArgumentException("No such profile");
        }
        byte[] data = readBytes(session, MAX_UPLOAD_BYTES);
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) throw new IllegalArgumentException("That file isn't an image");
        int side = Math.min(bitmap.getWidth(), bitmap.getHeight());
        bitmap = android.graphics.Bitmap.createBitmap(bitmap, (bitmap.getWidth() - side) / 2,
                (bitmap.getHeight() - side) / 2, side, side);
        if (side > AVATAR_PX) bitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, AVATAR_PX, AVATAR_PX, true);
        File file = Profiles.avatarFile(mContext, id);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        return new JSONObject().put("ok", true);
    }

    private Response serveAvatar(String uri) throws IOException {
        String name = uri.substring("/avatars/".length());
        File file = name.matches("[a-z0-9]+\\.png")
                ? Profiles.avatarFile(mContext, name.substring(0, name.length() - 4)) : null;
        if (file == null || !file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        return newFixedLengthResponse(Response.Status.OK, "image/png", new FileInputStream(file), file.length());
    }

    private Response serveLogo(String uri) throws IOException {
        String name = uri.substring("/logos/".length());
        if (!name.matches("\\d+\\.png")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        File file = ChannelConfig.customLogoFile(mContext,
                Integer.parseInt(name.substring(0, name.length() - 4)));
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        return newFixedLengthResponse(Response.Status.OK, "image/png", new FileInputStream(file),
                file.length());
    }

    private static byte[] readBytes(IHTTPSession session, int max) throws IOException {
        String length = session.getHeaders().get("content-length");
        int size = length == null ? 0 : Integer.parseInt(length);
        if (size <= 0) throw new IllegalArgumentException("Empty upload");
        if (size > max) {
            throw new IllegalArgumentException("File is too large (max "
                    + (max / (1024 * 1024)) + " MB)");
        }
        byte[] data = new byte[size];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < size) {
            int n = in.read(data, read, size - read);
            if (n < 0) break;
            read += n;
        }
        return data;
    }

    // ---- Backup and restore ----

    /**
     * Builds a zip of everything needed to set BingeTV Redux up again: account settings (optionally
     * without the password), the channel config, uploaded logos and cached guide channel lists,
     * every profile (settings, watch history, recent channels, addons, picture) and the app's
     * own settings (subtitle style). Left out as they belong to this TV or are rebuilt: the web
     * page's PIN and paired browsers, display-mode memory, sync times and caches.
     */
    private static final int AVATAR_PX = 256;

    private Response backup(boolean includePassword) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            ChannelConfig config = ChannelConfig.load(mContext);
            SharedPreferences prefs = XtreamClient.prefs(mContext);
            JSONObject settings = new JSONObject()
                    .put("name", prefs.getString(XtreamClient.PREF_NAME, ""))
                    .put("server", prefs.getString(XtreamClient.PREF_SERVER, ""))
                    .put("username", prefs.getString(XtreamClient.PREF_USERNAME, ""))
                    .put("useHls", prefs.getBoolean(XtreamClient.PREF_USE_HLS, false));
            if (includePassword) {
                settings.put("password", prefs.getString(XtreamClient.PREF_PASSWORD, ""));
            }
            putEntry(zip, "manifest.json", new JSONObject().put("app", BACKUP_APP)
                    .put("version", BACKUP_VERSION).put("created", System.currentTimeMillis())
                    .toString(2).getBytes(StandardCharsets.UTF_8));
            putEntry(zip, "settings.json", settings.toString(2).getBytes(StandardCharsets.UTF_8));
            // Addon URLs hold their configuration (often keys), so they're always included:
            // without them the addons can't be restored.
            // Each profile's own (addons/ID.json); addons.json, the one in use, is for older versions.
            for (Profiles.Profile p : Profiles.all(mContext)) {
                putEntry(zip, "addons/" + p.id + ".json", Stremio.addons(mContext, p.id).toJson()
                        .toString(2).getBytes(StandardCharsets.UTF_8));
            }
            putEntry(zip, "addons.json", Stremio.addons(mContext).toJson().toString(2)
                    .getBytes(StandardCharsets.UTF_8));
            JSONObject profiles = Profiles.export(mContext);
            if (!includePassword) {
                // Without passwords, the MDBList keys stay behind too.
                JSONArray list = profiles.getJSONArray("profiles");
                for (int i = 0; i < list.length(); i++) {
                    list.getJSONObject(i).getJSONObject("settings").remove(Settings.MDBLIST_KEY);
                }
            }
            putEntry(zip, "profiles.json", profiles.toString().getBytes(StandardCharsets.UTF_8));
            // The app's own settings, shared by every profile.
            putEntry(zip, "app.json", new JSONObject()
                    .put("subtitleStyle", SubtitleStyle.toJson(mContext))
                    .put("liveSubtitles", PlayerActivity.subtitlesOn(mContext))
                    .toString(2).getBytes(StandardCharsets.UTF_8));
            JSONObject configJson = new JSONObject(config.toJson().toString());
            if (!includePassword) {
                JSONArray sources = configJson.optJSONArray("sources");
                for (int i = 0; sources != null && i < sources.length(); i++) {
                    sources.getJSONObject(i).remove("password");
                }
            }
            putEntry(zip, "config.json", configJson.toString(2)
                    .getBytes(StandardCharsets.UTF_8));
            // Only logos a channel uses: switching back to M3U/EPG leaves the old file behind.
            File[] avatars = new File(mContext.getFilesDir(), "avatars").listFiles();
            for (int i = 0; avatars != null && i < avatars.length; i++) {
                if (avatars[i].getName().matches("[a-z0-9]+\\.png")) {
                    putEntry(zip, "avatars/" + avatars[i].getName(), readFile(avatars[i]));
                }
            }
            File[] logos = new File(mContext.getFilesDir(), "logos").listFiles();
            if (logos != null) {
                for (File logo : logos) {
                    String name = logo.getName();
                    if (name.matches("\\d+\\.png") && ChannelConfig.LOGO_CUSTOM.equals(
                            config.logoSource(Integer.parseInt(name.substring(0, name.length() - 4))))) {
                        putEntry(zip, "logos/" + name, readFile(logo));
                    }
                }
            }
            File[] files = mContext.getFilesDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().matches("guide_[A-Za-z0-9_-]+\\.json")) {
                        putEntry(zip, "guides/" + f.getName(), readFile(f));
                    }
                }
            }
        }
        String name = "bingetv-redux-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm",
                Locale.ROOT).format(new java.util.Date()) + ".zip";
        Response response = newFixedLengthResponse(Response.Status.OK, "application/zip",
                new java.io.ByteArrayInputStream(bytes.toByteArray()), bytes.size());
        response.addHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    /**
     * Restores a backup zip, replacing settings, config, logos and guide lists, then starts a
     * sync. Entries are checked against the names a backup contains, so a zip can't write
     * anywhere else. A backup without a password keeps the current one. FyrTuner's backups
     * restore too: its live TV settings are the same.
     */
    private JSONObject restore(IHTTPSession session) throws Exception {
        byte[] data = readBytes(session, MAX_BACKUP_BYTES);
        JSONObject manifest = null;
        JSONObject settings = null;
        JSONObject config = null;
        JSONObject addons = null;
        Map<String, JSONObject> profileAddons = new HashMap<>();
        JSONObject profiles = null;
        JSONObject appSettings = null;
        Map<String, byte[]> logos = new HashMap<>();
        Map<String, byte[]> avatars = new HashMap<>();
        Map<String, byte[]> guides = new HashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] content = readAll(zip);
                if ("manifest.json".equals(name)) {
                    manifest = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if ("settings.json".equals(name)) {
                    settings = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if ("app.json".equals(name)) {
                    appSettings = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if ("profiles.json".equals(name)) {
                    profiles = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if (name.matches("addons/[a-z0-9]+\\.json")) {
                    profileAddons.put(name.substring("addons/".length(), name.length() - 5),
                            new JSONObject(new String(content, StandardCharsets.UTF_8)));
                } else if ("addons.json".equals(name)) {
                    addons = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if ("config.json".equals(name)) {
                    config = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if (name.matches("avatars/[a-z0-9]+\\.png")) {
                    avatars.put(name.substring("avatars/".length()), content);
                } else if (name.matches("logos/\\d+\\.png")) {
                    logos.put(name.substring("logos/".length()), content);
                } else if (name.matches("guides/guide_[A-Za-z0-9_-]+\\.json")) {
                    guides.put(name.substring("guides/".length()), content);
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException(NOT_A_BACKUP);
        }
        String app = manifest == null ? "" : manifest.optString("app");
        boolean fromFyrTuner = "FyrTuner".equals(app);
        if (!(fromFyrTuner || BACKUP_APP.equals(app)) || settings == null || config == null) {
            throw new IllegalArgumentException(NOT_A_BACKUP);
        }
        if (!fromFyrTuner && manifest.optInt("version") > BACKUP_VERSION) {
            throw new IllegalArgumentException("This backup is from a newer BingeTV Redux; update first");
        }

        SharedPreferences.Editor editor = XtreamClient.prefs(mContext).edit()
                .putString(XtreamClient.PREF_NAME, settings.optString("name"))
                .putString(XtreamClient.PREF_SERVER, settings.optString("server"))
                .putString(XtreamClient.PREF_USERNAME, settings.optString("username"))
                .putBoolean(XtreamClient.PREF_USE_HLS, settings.optBoolean("useHls"));
        boolean hasPassword = !settings.optString("password", "").isEmpty();
        if (hasPassword) editor.putString(XtreamClient.PREF_PASSWORD, settings.getString("password"));
        editor.commit();
        JSONArray restoredSources = config.optJSONArray("sources");
        if (restoredSources != null) {
            ChannelConfig current = ChannelConfig.load(mContext);
            for (int i = 0; i < restoredSources.length(); i++) {
                JSONObject src = restoredSources.getJSONObject(i);
                JSONObject existing = current.source(src.optInt("index"));
                if (src.optString("password", "").isEmpty() && existing != null) {
                    src.put("password", existing.optString("password"));
                }
            }
        }
        ChannelConfig.replace(mContext, config);
        if (appSettings != null) {
            JSONObject style = appSettings.optJSONObject("subtitleStyle");
            if (style != null) SubtitleStyle.restore(mContext, style);
            PlayerActivity.setSubtitlesOn(mContext, appSettings.optBoolean("liveSubtitles"));
        }
        // FyrTuner's backups have no addons: the ones here stay. (Applied once the profiles are.)
        if (profiles != null) {
            Profiles.restore(mContext, profiles);
            Stremio.reloadAddons();
            // Profile pictures: the backup's replace any here.
            File[] old = new File(mContext.getFilesDir(), "avatars").listFiles();
            for (int i = 0; old != null && i < old.length; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
            for (Map.Entry<String, byte[]> avatar : avatars.entrySet()) {
                File file = Profiles.avatarFile(mContext, avatar.getKey().replace(".png", ""));
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(avatar.getValue());
                }
            }
        }

        File logoDir = new File(mContext.getFilesDir(), "logos");
        File[] oldLogos = logoDir.listFiles();
        if (oldLogos != null) {
            for (File f : oldLogos) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        logoDir.mkdirs();
        for (Map.Entry<String, byte[]> logo : logos.entrySet()) {
            writeFile(new File(logoDir, logo.getKey()), logo.getValue());
        }
        for (Map.Entry<String, byte[]> guide : guides.entrySet()) {
            writeFile(new File(mContext.getFilesDir(), guide.getKey()), guide.getValue());
        }
        mLiveApi.clearCaches();

        ChannelSyncWorker.syncNow(mContext);
        ChannelSyncWorker.schedule(mContext, ChannelSyncWorker.SYNC_INTERVAL_HOURS);
        // Each profile's addons: from the backup's own, else (an older backup) its one set for all.
        if (!profileAddons.isEmpty() || addons != null) {
            for (Profiles.Profile p : Profiles.all(mContext)) {
                JSONObject own = profileAddons.containsKey(p.id) ? profileAddons.get(p.id) : addons;
                if (own != null) Stremio.addons(mContext, p.id).replace(own);
            }
        }
        return mLiveApi.state().put("restored", new JSONObject()
                .put("logos", logos.size())
                .put("guides", guides.size())
                .put("addons", addons == null && profileAddons.isEmpty() ? -1 : Stremio.addons(mContext).all().size())
                .put("passwordRestored", hasPassword));
    }

    private static void putEntry(java.util.zip.ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    // ---- Plumbing ----
    private static JSONObject readBody(IHTTPSession session) throws IOException, JSONException {
        String length = session.getHeaders().get("content-length");
        int size = length == null ? 0 : Integer.parseInt(length);
        if (size <= 0) return new JSONObject();
        byte[] data = new byte[size];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < size) {
            int n = in.read(data, read, size - read);
            if (n < 0) break;
            read += n;
        }
        return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
    }

    /**
     * Pairs a browser that sends the TV's PIN: its token goes in a cookie for a year, and
     * every other API call needs it.
     */
    private Response pair(JSONObject body) throws JSONException {
        try {
            String token = WebAuth.pair(mContext, body.optString("pin"));
            Response response = json(Response.Status.OK, new JSONObject().put("ok", true));
            response.addHeader("Set-Cookie", WebAuth.COOKIE + "=" + token
                    + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict");
            return response;
        } catch (WebAuth.PairingException e) {
            return error(e.locked ? Response.Status.TOO_MANY_REQUESTS
                    : Response.Status.FORBIDDEN, e.getMessage());
        }
    }

    /** The page and its scripts and images, from assets/web. Anyone may load these. */
    private Response serveAsset(String uri) throws IOException {
        String name = "/".equals(uri) ? "index.html" : uri.substring(1);
        if (!name.matches("[a-z0-9_-]+\\.(html|js|css|png)")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        InputStream in;
        try {
            in = mContext.getAssets().open("web/" + name);
        } catch (java.io.FileNotFoundException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8" : "image/png";
        Response response = newChunkedResponse(Response.Status.OK, type, in);
        response.addHeader("Cache-Control", name.endsWith(".png") ? "max-age=86400" : "no-store");
        return response;
    }

    private static Response json(Response.Status status, Object body) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8",
                body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static Response error(Response.Status status, String message) {
        try {
            return json(status, new JSONObject().put("error",
                    message == null ? "Unknown error" : message));
        } catch (JSONException e) {
            return newFixedLengthResponse(status, MIME_PLAINTEXT, String.valueOf(message));
        }
    }
}
