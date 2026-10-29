package com.shiggsy365.bingetvredux.stremio;

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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The installed Stremio addons, saved as JSON, and how their catalogs are organised: which hub
 * each is in, its name, whether it's shown and searched, and the order of each.
 *
 * Addons are keyed by transport URL rather than manifest id, so the same addon can be added
 * more than once with different configurations. Every change saves straight away. Shared by
 * the web server and the TV screens, so every method is synchronized.
 */
public final class AddonStore {
    /** The hub whose catalogs are on Home. */
    public static final String HOME = "home";
    public static final List<String> HUBS =
            Collections.unmodifiableList(Arrays.asList("home", "movies", "tvshows", "anime", "more"));
    private static final Set<String> HIDDEN_BY_DEFAULT_TYPES =
            new HashSet<>(Collections.singletonList("music"));
    private static final int SCHEMA_VERSION = 1;
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}\\x{2B00}-\\x{2BFF}"
                    + "\\x{2300}-\\x{23FF}]+");

    /** How the user has organised one catalog. Empty strings mean "the addon's own". */
    public static final class CatalogPrefs {
        public boolean shown = true;    // shown in its hub
        public boolean search = true;   // used by Search
        public String name = "";        // the user's name for it when browsing
        public String searchName = "";  // the user's name for it in search
        public String hub = "";         // the hub it's in ("" = the usual one for its type)

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("home", shown).put("search", search);
            if (!name.isEmpty()) o.put("name", name);
            if (!searchName.isEmpty()) o.put("searchName", searchName);
            if (!hub.isEmpty()) o.put("hub", hub);
            return o;
        }

        static CatalogPrefs fromJson(JSONObject o) {
            CatalogPrefs p = new CatalogPrefs();
            p.shown = o.optBoolean("home", true);
            p.search = o.optBoolean("search", true);
            p.name = o.optString("name", "");
            p.searchName = o.optString("searchName", "");
            p.hub = o.optString("hub", "");
            return p;
        }
    }

    public static final class Addon {
        public final String transportUrl;
        public Manifest manifest;
        public boolean enabled = true;
        public long fetchedAt;
        final Map<String, CatalogPrefs> catalogPrefs = new HashMap<>();
        private final String mKey;

        Addon(String transportUrl, Manifest manifest) {
            this.transportUrl = transportUrl;
            this.manifest = manifest;
            mKey = shortHash(transportUrl);
        }

        /** A short id that stands for the addon, so its URL (with tokens) stays private. */
        public String key() {
            return mKey;
        }

        private static String shortHash(String text) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-1")
                        .digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 5; i++) sb.append(String.format(Locale.ROOT, "%02x", digest[i]));
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        public CatalogPrefs prefs(Manifest.Catalog catalog) {
            CatalogPrefs p = catalogPrefs.get(catalog.key());
            if (p != null) return p;
            p = new CatalogPrefs();
            p.shown = !HIDDEN_BY_DEFAULT_TYPES.contains(catalog.type);
            return p;
        }

        public String hubOf(Manifest.Catalog catalog) {
            String hub = prefs(catalog).hub;
            return hub.isEmpty() ? defaultHub(catalog.type) : hub;
        }

        /** The catalog's name when browsing: the user's, else the addon's without emoji. */
        public String displayName(Manifest.Catalog catalog) {
            String name = prefs(catalog).name;
            return name.isEmpty() ? tidyName(catalog.name) : name;
        }

        /** The catalog's name in search: the user's, else the addon's. */
        public String searchName(Manifest.Catalog catalog) {
            String name = prefs(catalog).searchName;
            return name.isEmpty() ? catalog.name : name;
        }

        JSONObject toJson() throws JSONException {
            JSONObject prefs = new JSONObject();
            for (Map.Entry<String, CatalogPrefs> e : catalogPrefs.entrySet()) {
                prefs.put(e.getKey(), e.getValue().toJson());
            }
            return new JSONObject()
                    .put("transportUrl", transportUrl)
                    .put("manifest", manifest.raw)
                    .put("enabled", enabled)
                    .put("fetchedAt", fetchedAt)
                    .put("catalogPrefs", prefs);
        }
    }

    /** A catalog with the addon it belongs to. */
    public static final class Entry {
        public final Addon addon;
        public final Manifest.Catalog catalog;

        Entry(Addon addon, Manifest.Catalog catalog) {
            this.addon = addon;
            this.catalog = catalog;
        }

        /** "addonkey|movie/top": identifies a catalog across addons. */
        public String key() {
            return addon.key() + "|" + catalog.key();
        }

        public CatalogPrefs prefs() {
            return addon.prefs(catalog);
        }
    }

    public static final class HubInfo {
        public final String id;
        public final String name;
        public final boolean custom;

        HubInfo(String id, String name, boolean custom) {
            this.id = id;
            this.name = name;
            this.custom = custom;
        }
    }

    private final File mFile;
    private final List<Addon> mAddons = new ArrayList<>();
    private final List<String> mSearchOrder = new ArrayList<>();
    private final List<String> mHubOrder = new ArrayList<>();
    // The user's own hubs, id -> name, in creation order.
    private final LinkedHashMap<String, String> mCustomHubs = new LinkedHashMap<>();
    // Built-in hub -> the user's name for it.
    private final Map<String, String> mHubNames = new HashMap<>();

    public AddonStore(File file) {
        mFile = file;
        load();
    }

    public static String defaultHub(String type) {
        if ("movie".equals(type)) return "movies";
        if ("series".equals(type)) return "tvshows";
        if (type.toLowerCase(Locale.ROOT).contains("anime")) return "anime";
        return "more";
    }

    /** A catalog name without emoji and doubled spaces ("Netflix 🍿" becomes "Netflix"). */
    public static String tidyName(String name) {
        String tidy = EMOJI.matcher(name).replaceAll("").replaceAll("\\s{2,}", " ").trim();
        return tidy.isEmpty() ? name : tidy;
    }

    // ---- Persistence ----

    private void load() {
        if (!mFile.exists()) return;
        try {
            JSONObject data = new JSONObject(readFile(mFile));
            JSONArray hubs = data.optJSONArray("customHubs");
            for (int i = 0; hubs != null && i < hubs.length(); i++) {
                JSONObject h = hubs.optJSONObject(i);
                if (h != null && !HUBS.contains(h.optString("id"))
                        && !h.optString("id").isEmpty()) {
                    mCustomHubs.put(h.optString("id"), h.optString("name"));
                }
            }
            JSONObject names = data.optJSONObject("hubNames");
            if (names != null) {
                for (String hub : HUBS) {
                    if (!names.optString(hub).isEmpty()) mHubNames.put(hub, names.optString(hub));
                }
            }
            mSearchOrder.addAll(Manifest.strings(data.optJSONArray("searchOrder")));
            mHubOrder.addAll(Manifest.strings(data.optJSONArray("hubOrder")));
            JSONArray addons = data.optJSONArray("addons");
            for (int i = 0; addons != null && i < addons.length(); i++) {
                JSONObject a = addons.getJSONObject(i);
                Addon addon = new Addon(a.getString("transportUrl"),
                        Manifest.fromJson(a.getJSONObject("manifest")));
                addon.enabled = a.optBoolean("enabled", true);
                addon.fetchedAt = a.optLong("fetchedAt");
                JSONObject prefs = a.optJSONObject("catalogPrefs");
                if (prefs != null) {
                    for (java.util.Iterator<String> it = prefs.keys(); it.hasNext(); ) {
                        String key = it.next();
                        CatalogPrefs p = CatalogPrefs.fromJson(prefs.getJSONObject(key));
                        // A catalog in a hub that's gone goes back to its usual one.
                        if (!p.hub.isEmpty() && !hubIds().contains(p.hub)) p.hub = "";
                        addon.catalogPrefs.put(key, p);
                    }
                }
                mAddons.add(addon);
            }
        } catch (IOException | JSONException | StremioException e) {
            // Keep the broken file to look at rather than overwriting it.
            mAddons.clear();
            //noinspection ResultOfMethodCallIgnored
            mFile.renameTo(new File(mFile.getPath() + ".corrupt"));
        }
    }

    private void save() {
        try {
            JSONArray addons = new JSONArray();
            for (Addon a : mAddons) addons.put(a.toJson());
            JSONArray hubs = new JSONArray();
            for (Map.Entry<String, String> h : mCustomHubs.entrySet()) {
                hubs.put(new JSONObject().put("id", h.getKey()).put("name", h.getValue()));
            }
            JSONObject data = new JSONObject()
                    .put("version", SCHEMA_VERSION)
                    .put("addons", addons)
                    .put("searchOrder", new JSONArray(mSearchOrder))
                    .put("hubOrder", new JSONArray(mHubOrder))
                    .put("customHubs", hubs)
                    .put("hubNames", new JSONObject(mHubNames));
            File tmp = new File(mFile.getPath() + ".tmp");
            File dir = mFile.getParentFile();
            if (dir != null) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(mFile)) throw new IOException("Could not replace " + mFile);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Could not save the addons", e);
        }
    }

    /** The whole store as saved, for backups. */
    public synchronized JSONObject toJson() {
        try {
            return new JSONObject(readFile(mFile));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    /** Replaces everything with a backup's copy. */
    public synchronized void replace(JSONObject data) throws IOException {
        try (OutputStream out = new FileOutputStream(mFile)) {
            out.write(data.toString().getBytes(StandardCharsets.UTF_8));
        }
        mAddons.clear();
        mSearchOrder.clear();
        mHubOrder.clear();
        mCustomHubs.clear();
        mHubNames.clear();
        load();
    }

    // ---- Addons ----

    public synchronized List<Addon> all() {
        return new ArrayList<>(mAddons);
    }

    public synchronized List<Addon> enabled() {
        List<Addon> out = new ArrayList<>();
        for (Addon a : mAddons) if (a.enabled) out.add(a);
        return out;
    }

    /** By key or transport URL. */
    public synchronized Addon get(String ref) throws StremioException {
        for (Addon a : mAddons) {
            if (a.key().equals(ref) || a.transportUrl.equals(ref)) return a;
        }
        throw new StremioException("That addon isn't installed");
    }

    /** Enabled addons that can answer {@code resource} for a type (and id), in the user's order. */
    public synchronized List<Addon> addonsFor(String resource, String type, String id) {
        List<Addon> out = new ArrayList<>();
        for (Addon a : mAddons) {
            if (a.enabled && a.manifest.supports(resource, type, id)) out.add(a);
        }
        return out;
    }

    public synchronized Addon add(String transportUrl, Manifest manifest, long now)
            throws StremioException {
        for (Addon a : mAddons) {
            if (a.transportUrl.equals(transportUrl)) {
                throw new StremioException(a.manifest.name + " is already added with that URL");
            }
        }
        Addon addon = new Addon(transportUrl, manifest);
        addon.fetchedAt = now;
        mAddons.add(addon);
        save();
        return addon;
    }

    public synchronized void remove(String ref) throws StremioException {
        mAddons.remove(get(ref));
        save();
    }

    /** Moves an addon {@code delta} places (negative is up), within the list. */
    public synchronized void move(String ref, int delta) throws StremioException {
        Addon addon = get(ref);
        int index = mAddons.indexOf(addon);
        int target = Math.max(0, Math.min(mAddons.size() - 1, index + delta));
        if (target == index) return;
        mAddons.add(target, mAddons.remove(index));
        save();
    }

    public synchronized void setEnabled(String ref, boolean enabled) throws StremioException {
        get(ref).enabled = enabled;
        save();
    }

    /**
     * Swaps an addon's URL for a new one, e.g. after changing its configuration: it keeps its
     * place, whether it's on, and how its catalogs are organised.
     */
    public synchronized Addon replaceUrl(String ref, String transportUrl, Manifest manifest,
            long now) throws StremioException {
        Addon old = get(ref);
        for (Addon a : mAddons) {
            if (a != old && a.transportUrl.equals(transportUrl)) {
                throw new StremioException(a.manifest.name + " is already added with that URL");
            }
        }
        Addon addon = new Addon(transportUrl, manifest);
        addon.enabled = old.enabled;
        addon.fetchedAt = now;
        Set<String> keys = new HashSet<>();
        for (Manifest.Catalog c : manifest.catalogs) keys.add(c.key());
        for (Map.Entry<String, CatalogPrefs> e : old.catalogPrefs.entrySet()) {
            if (keys.contains(e.getKey())) addon.catalogPrefs.put(e.getKey(), e.getValue());
        }
        mAddons.set(mAddons.indexOf(old), addon);
        String from = old.key() + "|", to = addon.key() + "|";
        for (List<String> order : Arrays.asList(mHubOrder, mSearchOrder)) {
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).startsWith(from)) {
                    order.set(i, to + order.get(i).substring(from.length()));
                }
            }
        }
        save();
        return addon;
    }

    /** A newer manifest; preferences of catalogs the addon no longer has are dropped. */
    public synchronized void updateManifest(String ref, Manifest manifest, long now)
            throws StremioException {
        Addon addon = get(ref);
        addon.manifest = manifest;
        addon.fetchedAt = now;
        Set<String> keys = new HashSet<>();
        for (Manifest.Catalog c : manifest.catalogs) keys.add(c.key());
        addon.catalogPrefs.keySet().retainAll(keys);
        save();
    }

    // ---- Hubs ----

    private List<String> hubIds() {
        List<String> ids = new ArrayList<>(HUBS);
        ids.addAll(mCustomHubs.keySet());
        return ids;
    }

    /** Every hub: the built-in ones, then the user's own. */
    public synchronized List<HubInfo> hubs() {
        List<HubInfo> out = new ArrayList<>();
        for (String hub : HUBS) {
            String name = mHubNames.get(hub);
            out.add(new HubInfo(hub, name != null ? name : builtInHubName(hub), false));
        }
        for (Map.Entry<String, String> h : mCustomHubs.entrySet()) {
            out.add(new HubInfo(h.getKey(), h.getValue(), true));
        }
        return out;
    }

    public static String builtInHubName(String hub) {
        switch (hub) {
            case "home": return "Home";
            case "movies": return "Movies";
            case "tvshows": return "TV Shows";
            case "anime": return "Anime";
            default: return "More";
        }
    }

    public synchronized String createHub(String name) throws StremioException {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new StremioException("A hub needs a name");
        int n = 1;
        while (mCustomHubs.containsKey("c" + n)) n++;
        mCustomHubs.put("c" + n, name);
        save();
        return "c" + n;
    }

    /** Renames a hub; an empty name gives a built-in hub its own name back. */
    public synchronized void renameHub(String hub, String name) throws StremioException {
        name = name == null ? "" : name.trim();
        if (mCustomHubs.containsKey(hub)) {
            if (name.isEmpty()) throw new StremioException("A hub needs a name");
            mCustomHubs.put(hub, name);
        } else if (HUBS.contains(hub)) {
            if (name.isEmpty()) mHubNames.remove(hub);
            else mHubNames.put(hub, name);
        } else {
            throw new StremioException("There's no hub " + hub);
        }
        save();
    }

    /** Removes one of the user's hubs; its catalogs go back to their usual hubs. */
    public synchronized void removeHub(String hub) throws StremioException {
        if (mCustomHubs.remove(hub) == null) {
            throw new StremioException("Only hubs you made can be removed");
        }
        for (Addon a : mAddons) {
            for (CatalogPrefs p : a.catalogPrefs.values()) if (p.hub.equals(hub)) p.hub = "";
        }
        save();
    }

    // ---- Catalogs ----

    /** Every browsable catalog of enabled addons, in the user's order. */
    public synchronized List<Entry> browsable() {
        List<Entry> entries = new ArrayList<>();
        for (Addon a : mAddons) {
            if (!a.enabled) continue;
            for (Manifest.Catalog c : a.manifest.catalogs) {
                if (c.isBrowsable()) entries.add(new Entry(a, c));
            }
        }
        return ordered(entries, mHubOrder);
    }

    /** Every catalog in a hub, shown or not, in order (for organising). */
    public synchronized List<Entry> hubEntries(String hub) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : browsable()) if (e.addon.hubOf(e.catalog).equals(hub)) out.add(e);
        return out;
    }

    /** The catalogs shown in a hub, in order. */
    public synchronized List<Entry> hubCatalogs(String hub) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : hubEntries(hub)) if (e.prefs().shown) out.add(e);
        return out;
    }

    /** Hubs that have catalogs to show. */
    public synchronized List<HubInfo> hubsInUse() {
        List<HubInfo> out = new ArrayList<>();
        for (HubInfo h : hubs()) if (!hubCatalogs(h.id).isEmpty()) out.add(h);
        return out;
    }

    public synchronized Entry entry(String key) throws StremioException {
        int bar = key.indexOf('|');
        if (bar < 0) throw new StremioException("There's no catalog " + key);
        Addon addon = get(key.substring(0, bar));
        Manifest.Catalog catalog = addon.manifest.catalog(key.substring(bar + 1));
        if (catalog == null) throw new StremioException("There's no catalog " + key);
        return new Entry(addon, catalog);
    }

    /** Moves a catalog within its hub; other hubs keep their order. */
    public synchronized void moveInHub(String key, int delta) throws StremioException {
        List<String> order = keys(browsable());
        if (!order.contains(key)) throw new StremioException("There's no catalog " + key);
        String hub = hubOfKey(key);
        List<Integer> slots = new ArrayList<>();
        List<String> members = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            if (hub.equals(hubOfKey(order.get(i)))) {
                slots.add(i);
                members.add(order.get(i));
            }
        }
        int index = members.indexOf(key);
        int target = Math.max(0, Math.min(members.size() - 1, index + delta));
        members.add(target, members.remove(index));
        for (int i = 0; i < slots.size(); i++) order.set(slots.get(i), members.get(i));
        mHubOrder.clear();
        mHubOrder.addAll(order);
        save();
    }

    private String hubOfKey(String key) throws StremioException {
        Entry e = entry(key);
        return e.addon.hubOf(e.catalog);
    }

    /**
     * Changes one catalog's preferences. Null leaves a field as it is; for names and hub an
     * empty string restores the addon's own.
     */
    public synchronized void setCatalogPrefs(String key, Boolean shown, Boolean search,
            String name, String searchName, String hub) throws StremioException {
        Entry e = entry(key);
        CatalogPrefs p = e.addon.catalogPrefs.get(e.catalog.key());
        if (p == null) {
            p = e.addon.prefs(e.catalog);
            e.addon.catalogPrefs.put(e.catalog.key(), p);
        }
        if (shown != null) p.shown = shown;
        if (search != null) p.search = search;
        if (name != null) p.name = name.trim();
        if (searchName != null) p.searchName = searchName.trim();
        if (hub != null) p.hub = hubIds().contains(hub) ? hub : "";
        save();
    }

    // ---- Search ----

    /** Every catalog that can be searched with just a query, in the user's search order. */
    public synchronized List<Entry> searchEntries() {
        List<Entry> entries = new ArrayList<>();
        for (Addon a : mAddons) {
            if (!a.enabled) continue;
            for (Manifest.Catalog c : a.manifest.catalogs) {
                if (c.isSearchableAlone()) entries.add(new Entry(a, c));
            }
        }
        return ordered(entries, mSearchOrder);
    }

    /** The catalogs Search uses, in order. */
    public synchronized List<Entry> searchCatalogs() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : searchEntries()) if (e.prefs().search) out.add(e);
        return out;
    }

    public synchronized void moveSearchCatalog(String key, int delta) throws StremioException {
        List<String> order = keys(searchEntries());
        int index = order.indexOf(key);
        if (index < 0) throw new StremioException("There's no search catalog " + key);
        int target = Math.max(0, Math.min(order.size() - 1, index + delta));
        order.add(target, order.remove(index));
        mSearchOrder.clear();
        mSearchOrder.addAll(order);
        save();
    }

    // ---- Helpers ----

    /** Entries in the saved order; ones not in it yet (newly added) follow in addon order. */
    private static List<Entry> ordered(List<Entry> entries, List<String> order) {
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) indexes.add(i);
        Collections.sort(indexes, (x, y) -> {
            int rx = rank.getOrDefault(entries.get(x).key(), order.size());
            int ry = rank.getOrDefault(entries.get(y).key(), order.size());
            return rx != ry ? Integer.compare(rx, ry) : Integer.compare(x, y);
        });
        List<Entry> out = new ArrayList<>();
        for (int i : indexes) out.add(entries.get(i));
        return out;
    }

    private static List<String> keys(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.key());
        return out;
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
