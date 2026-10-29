package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A Stremio addon's manifest: what it is, and what it can answer. */
public final class Manifest {
    public final String id;
    public final String name;
    public final String version;
    public final String description;
    public final String logo;
    public final List<String> types;
    /** Null when the manifest doesn't limit ids. */
    public final List<String> idPrefixes;
    public final List<Resource> resources;
    public final List<Catalog> catalogs;
    public final boolean configurable;
    public final boolean configurationRequired;
    public final boolean adult;
    public final boolean p2p;
    /** The manifest as the addon sent it, for saving. */
    public final JSONObject raw;

    /** A catalog's optional or required input, e.g. genre, search or skip. */
    public static final class Extra {
        public final String name;
        public final boolean isRequired;
        public final List<String> options;

        Extra(String name, boolean isRequired, List<String> options) {
            this.name = name;
            this.isRequired = isRequired;
            this.options = options;
        }
    }

    public static final class Catalog {
        public final String type;
        public final String id;
        public final String name;
        public final List<Extra> extra;
        /** The step for "skip", when the addon states it; 0 otherwise. */
        public final int pageSize;
        /** The addon's "showInHome": null when it doesn't say. */
        public final Boolean showInHome;

        Catalog(String type, String id, String name, List<Extra> extra, int pageSize,
                Boolean showInHome) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.extra = extra;
            this.pageSize = pageSize;
            this.showInHome = showInHome;
        }

        /** Unique within an addon: "movie/top". */
        public String key() {
            return type + "/" + id;
        }

        public Extra extra(String name) {
            for (Extra e : extra) if (e.name.equals(name)) return e;
            return null;
        }

        public boolean isSearchable() {
            return extra("search") != null;
        }

        /** Searchable with nothing but a query (no other required input). */
        public boolean isSearchableAlone() {
            if (!isSearchable()) return false;
            for (Extra e : extra) if (!e.name.equals("search") && e.isRequired) return false;
            return true;
        }

        /**
         * Can be browsed: anything it requires has options to pick from (Cinemeta's "by year"
         * needs a year, for example), so search-only catalogs are out.
         */
        public boolean isBrowsable() {
            for (Extra e : extra) if (e.isRequired && e.options.isEmpty()) return false;
            return true;
        }

        public boolean isSearchOnly() {
            Extra search = extra("search");
            return search != null && search.isRequired;
        }

        /** AIOMetadata's people search, which returns a person's films and shows. */
        public boolean isPeopleSearch() {
            return id.startsWith("people_search");
        }

        /** Whether the addon wants it on Home: yes unless search-only or it says no. */
        public boolean defaultOnHome() {
            return !isSearchOnly() && !Boolean.FALSE.equals(showInHome);
        }

        public boolean supportsPaging() {
            return extra("skip") != null;
        }

        /** Extras the user picks a value for, such as genre. */
        public List<Extra> filters() {
            List<Extra> out = new ArrayList<>();
            for (Extra e : extra) {
                if (!e.options.isEmpty() && !e.name.equals("search") && !e.name.equals("skip")) {
                    out.add(e);
                }
            }
            return out;
        }

        static Catalog fromJson(JSONObject data) {
            String type = data.optString("type", "");
            String id = data.optString("id", "");
            if (type.isEmpty() || id.isEmpty()) return null;
            List<Extra> extra = new ArrayList<>();
            JSONArray extraJson = data.optJSONArray("extra");
            if (extraJson != null) {
                for (int i = 0; i < extraJson.length(); i++) {
                    JSONObject e = extraJson.optJSONObject(i);
                    if (e == null || !(e.opt("name") instanceof String)) continue;
                    extra.add(new Extra(e.optString("name"), e.optBoolean("isRequired"),
                            strings(e.optJSONArray("options"))));
                }
            } else {
                // Older manifests: extraSupported / extraRequired / genres.
                List<String> required = strings(data.optJSONArray("extraRequired"));
                List<String> genres = strings(data.optJSONArray("genres"));
                for (String name : strings(data.optJSONArray("extraSupported"))) {
                    extra.add(new Extra(name, required.contains(name),
                            name.equals("genre") ? genres : Collections.emptyList()));
                }
            }
            // Some addons mark a search catalog this way instead of with a "search" extra.
            if (data.optBoolean("isSearch") && !hasExtra(extra, "search")) {
                extra.add(new Extra("search", true, Collections.emptyList()));
            }
            int pageSize = data.optInt("pageSize", 0);
            Object showInHome = data.opt("showInHome");
            String name = data.optString("name", "");
            return new Catalog(type, id, name.isEmpty() ? id : name, extra,
                    Math.max(pageSize, 0),
                    showInHome instanceof Boolean ? (Boolean) showInHome : null);
        }

        private static boolean hasExtra(List<Extra> extra, String name) {
            for (Extra e : extra) if (e.name.equals(name)) return true;
            return false;
        }
    }

    /** A resource (catalog, meta, stream, subtitles); null lists inherit the manifest's. */
    public static final class Resource {
        public final String name;
        public final List<String> types;
        public final List<String> idPrefixes;

        Resource(String name, List<String> types, List<String> idPrefixes) {
            this.name = name;
            this.types = types;
            this.idPrefixes = idPrefixes;
        }
    }

    private Manifest(JSONObject data, List<Resource> resources, List<Catalog> catalogs) {
        JSONObject hints = data.optJSONObject("behaviorHints");
        if (hints == null) hints = new JSONObject();
        id = data.optString("id");
        name = data.optString("name");
        String v = data.optString("version", "");
        version = v.isEmpty() ? "0.0.0" : v;
        description = data.optString("description", "");
        logo = data.optString("logo", "");
        types = strings(data.optJSONArray("types"));
        idPrefixes = data.has("idPrefixes") ? strings(data.optJSONArray("idPrefixes")) : null;
        this.resources = resources;
        this.catalogs = catalogs;
        configurable = hints.optBoolean("configurable");
        configurationRequired = hints.optBoolean("configurationRequired");
        adult = hints.optBoolean("adult");
        p2p = hints.optBoolean("p2p");
        raw = data;
    }

    public static Manifest fromJson(JSONObject data) throws StremioException {
        if (data == null) throw new StremioException("The manifest isn't a JSON object");
        for (String key : new String[]{"id", "name"}) {
            if (!(data.opt(key) instanceof String) || data.optString(key).isEmpty()) {
                throw new StremioException("The manifest has no '" + key + "'");
            }
        }
        JSONArray resourcesJson = data.optJSONArray("resources");
        if (resourcesJson == null) throw new StremioException("The manifest has no 'resources'");
        List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < resourcesJson.length(); i++) {
            Object value = resourcesJson.opt(i);
            if (value instanceof String) {
                resources.add(new Resource((String) value, null, null));
            } else if (value instanceof JSONObject) {
                JSONObject r = (JSONObject) value;
                if (!(r.opt("name") instanceof String)) continue;
                resources.add(new Resource(r.optString("name"),
                        r.has("types") ? strings(r.optJSONArray("types")) : null,
                        r.has("idPrefixes") ? strings(r.optJSONArray("idPrefixes")) : null));
            }
        }
        List<Catalog> catalogs = new ArrayList<>();
        JSONArray catalogsJson = data.optJSONArray("catalogs");
        for (int i = 0; catalogsJson != null && i < catalogsJson.length(); i++) {
            JSONObject c = catalogsJson.optJSONObject(i);
            // A malformed catalog is skipped rather than rejecting the whole addon.
            Catalog catalog = c == null ? null : Catalog.fromJson(c);
            if (catalog != null) catalogs.add(catalog);
        }
        return new Manifest(data, resources, catalogs);
    }

    public Catalog catalog(String key) {
        for (Catalog c : catalogs) if (c.key().equals(key)) return c;
        return null;
    }

    public List<String> resourceNames() {
        List<String> names = new ArrayList<>();
        for (Resource r : resources) if (!names.contains(r.name)) names.add(r.name);
        return names;
    }

    /**
     * Whether it can answer {@code resource} requests for {@code type} (and {@code id}, unless
     * null). Catalogs are per catalog: use {@link #catalogs} for those.
     */
    public boolean supports(String resource, String type, String id) {
        for (Resource r : resources) {
            if (!r.name.equals(resource)) continue;
            List<String> t = r.types != null ? r.types : types;
            if (!t.contains(type)) continue;
            List<String> prefixes = r.idPrefixes != null ? r.idPrefixes : idPrefixes;
            if (id == null || prefixes == null || prefixes.isEmpty()) return true;
            for (String p : prefixes) if (id.startsWith(p)) return true;
        }
        return false;
    }

    static List<String> strings(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object v = array.opt(i);
            if (v instanceof String) out.add((String) v);
        }
        return out;
    }
}
