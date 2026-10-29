package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Fetching, paging and searching catalogs. */
public final class Catalogs {
    // Addons rarely state a page size, and many (Cinemeta included) drop a few items from each
    // page, e.g. 49 or 48 of 50. So the first page's count is rounded up to a common page size,
    // "skip" advances by that, and a page counts as full when it's mostly filled.
    private static final int[] COMMON_PAGE_SIZES =
            {10, 20, 25, 30, 40, 50, 60, 75, 100, 150, 200, 250, 500};
    private static final double FULL_PAGE_RATIO = 0.8;
    private static final int MIN_FULL_PAGE = 10;
    public static final long TTL_MS = 30 * 60_000L;
    private static final long SEARCH_TIMEOUT_MS = 20_000;

    private Catalogs() {}

    /** One page of a catalog. */
    public static final class Page {
        public final List<MetaItem> items;
        /** Where the next page starts, or -1 if there probably isn't one. */
        public final int nextSkip;
        /** The page size settled on from the first page, to pass back for later pages. */
        public final int pageSize;

        Page(List<MetaItem> items, int nextSkip, int pageSize) {
            this.items = items;
            this.nextSkip = nextSkip;
            this.pageSize = pageSize;
        }
    }

    /**
     * The filters plus the first option of every required filter that isn't set, as Stremio
     * does (Cinemeta's "by year" opens on the latest year, for example).
     */
    public static Map<String, String> withDefaultFilters(Manifest.Catalog catalog,
            Map<String, String> filters) {
        Map<String, String> merged = new LinkedHashMap<>(filters);
        for (Manifest.Extra e : catalog.filters()) {
            String v = merged.get(e.name);
            if (e.isRequired && (v == null || v.isEmpty())) merged.put(e.name, e.options.get(0));
        }
        return merged;
    }

    /** Extra values in the manifest's order, which some addons rely on. */
    public static List<Map.Entry<String, String>> orderedExtra(Manifest.Catalog catalog,
            Map<String, String> filters, int skip) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : filters.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) values.put(e.getKey(), e.getValue());
        }
        if (skip > 0) values.put("skip", String.valueOf(skip));
        List<Map.Entry<String, String>> ordered = new ArrayList<>();
        for (Manifest.Extra e : catalog.extra) {
            String v = values.remove(e.name);
            if (v != null) ordered.add(new SimpleEntry<>(e.name, v));
        }
        for (Map.Entry<String, String> e : values.entrySet()) ordered.add(new SimpleEntry<>(e));
        return ordered;
    }

    /**
     * A page of a catalog. {@code pageSize} is the size from the first page (0 on the first
     * page).
     */
    public static Page fetch(StremioClient client, AddonStore.Addon addon,
            Manifest.Catalog catalog, Map<String, String> filters, int skip, int pageSize)
            throws StremioException {
        JSONObject data = client.resource(addon.transportUrl, "catalog", catalog.type,
                catalog.id, orderedExtra(catalog, withDefaultFilters(catalog, filters), skip),
                TTL_MS);
        return page(catalog, data, skip, pageSize);
    }

    /** The page as cached, fresh or not, or null; for showing something straight away. */
    public static Page peek(StremioClient client, AddonStore.Addon addon,
            Manifest.Catalog catalog, Map<String, String> filters) {
        ResponseCache.Hit hit = client.peek(addon.transportUrl, "catalog", catalog.type,
                catalog.id, orderedExtra(catalog, withDefaultFilters(catalog, filters), 0));
        if (hit == null) return null;
        try {
            return page(catalog, hit.data, 0, 0);
        } catch (StremioException e) {
            return null;
        }
    }

    static Page page(Manifest.Catalog catalog, JSONObject data, int skip, int pageSize)
            throws StremioException {
        JSONArray metas = data.optJSONArray("metas");
        if (metas == null) throw new StremioException("The catalog sent no items");
        List<MetaItem> items = new ArrayList<>();
        for (int i = 0; i < metas.length(); i++) {
            MetaItem item = MetaItem.preview(metas.optJSONObject(i), catalog.type);
            if (item != null) items.add(item);
        }
        int[] next = nextPage(catalog, skip, metas.length(), pageSize);
        return new Page(items, next == null ? -1 : next[0], next == null ? 0 : next[1]);
    }

    static int nominalPageSize(int count) {
        for (int size : COMMON_PAGE_SIZES) if (size >= count) return size;
        return count;
    }

    /**
     * {next skip, page size} if there's probably another page, else null. A declared
     * pageSize is only trusted if the first page agrees with it: some addons declare 50 but
     * serve 20, and stepping by 50 would skip 30 items a page.
     */
    static int[] nextPage(Manifest.Catalog catalog, int skip, int count, int pageSize) {
        if (!catalog.supportsPaging() || count == 0) return null;
        int size;
        if (pageSize > 0) {
            size = pageSize;
        } else if (catalog.pageSize > 0 && count >= catalog.pageSize * FULL_PAGE_RATIO) {
            size = catalog.pageSize;
        } else if (count < MIN_FULL_PAGE) {
            return null;
        } else {
            size = nominalPageSize(count);
        }
        if (count < size * FULL_PAGE_RATIO) return null;
        return new int[]{skip + size, size};
    }

    /** One search catalog's results. */
    public static final class SearchResult {
        public final AddonStore.Entry entry;
        public final List<MetaItem> items;
        /** Null when it worked. */
        public final String error;

        SearchResult(AddonStore.Entry entry, List<MetaItem> items, String error) {
            this.entry = entry;
            this.items = items;
            this.error = error;
        }
    }

    /** Searches the catalogs at once; results come back in the catalogs' order. */
    public static List<SearchResult> search(StremioClient client, List<AddonStore.Entry> entries,
            String query) {
        List<Callable<List<MetaItem>>> tasks = new ArrayList<>();
        for (AddonStore.Entry e : entries) {
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("search", query);
            tasks.add(() -> fetch(client, e.addon, e.catalog, filters, 0, 0).items);
        }
        List<Parallel.Result<List<MetaItem>>> results = Parallel.run(tasks, SEARCH_TIMEOUT_MS);
        List<SearchResult> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Parallel.Result<List<MetaItem>> r = results.get(i);
            out.add(new SearchResult(entries.get(i), r.ok() ? r.value : new ArrayList<>(),
                    r.ok() ? null : r.error.getMessage()));
        }
        return out;
    }
}
