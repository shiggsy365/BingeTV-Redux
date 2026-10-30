package com.shiggsy365.bingetvredux;

import android.content.Context;

import com.shiggsy365.bingetvredux.stremio.AddonStore;
import com.shiggsy365.bingetvredux.stremio.MetaItem;
import com.shiggsy365.bingetvredux.stremio.Metas;
import com.shiggsy365.bingetvredux.stremio.Parallel;
import com.shiggsy365.bingetvredux.stremio.ResponseCache;
import com.shiggsy365.bingetvredux.stremio.StremioClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Catalog items with the details of the first metadata addon in the user's list (e.g.
 * AIOMetadata above Cinemeta), whichever addon's catalog they came from: its poster, logo,
 * artwork and text win, and the catalog's own details only fill the gaps.
 */
final class PreferredMetas {
    private static final long TIMEOUT_MS = 20_000;

    private PreferredMetas() {}

    /** The addon whose details an item should have; null if it's the catalog's own (or none). */
    private static AddonStore.Addon preferred(Context context, AddonStore.Addon source, MetaItem item) {
        List<AddonStore.Addon> addons = Stremio.addons(context).addonsFor("meta", item.type, item.id);
        if (addons.isEmpty()) return null;
        AddonStore.Addon first = addons.get(0);
        return source != null && first.transportUrl.equals(source.transportUrl) ? null : first;
    }

    /** Applies what's already in the cache; quick enough to run before a row is shown. */
    static List<MetaItem> fromCache(Context context, AddonStore.Addon source, List<MetaItem> items) {
        StremioClient client = Stremio.client(context);
        List<MetaItem> out = new ArrayList<>();
        for (MetaItem item : items) {
            AddonStore.Addon first = preferred(context, source, item);
            ResponseCache.Hit hit = first == null ? null
                    : client.peek(first.transportUrl, "meta", item.type, item.id, null);
            MetaItem meta = hit == null ? null : MetaItem.full(hit.data.optJSONObject("meta"), item.type);
            out.add(meta == null ? item : item.preferring(meta));
        }
        return out;
    }

    /**
     * Asks the preferred addons for every item (from the cache while it's fresh), several at a
     * time. Items whose meta can't be had stay as they were.
     */
    static List<MetaItem> fetch(Context context, AddonStore.Addon source, List<MetaItem> items) {
        StremioClient client = Stremio.client(context);
        List<Callable<MetaItem>> tasks = new ArrayList<>();
        for (MetaItem item : items) {
            AddonStore.Addon first = preferred(context, source, item);
            tasks.add(() -> {
                if (first == null) return item;
                JSONObject data = client.resource(first.transportUrl, "meta", item.type, item.id,
                        null, Metas.TTL_MS);
                MetaItem meta = MetaItem.full(data.optJSONObject("meta"), item.type);
                return meta == null ? item : item.preferring(meta);
            });
        }
        List<MetaItem> out = new ArrayList<>();
        List<Parallel.Result<MetaItem>> results = Parallel.run(tasks, TIMEOUT_MS);
        for (int i = 0; i < items.size(); i++) {
            Parallel.Result<MetaItem> r = results.get(i);
            out.add(r.ok() && r.value != null ? r.value : items.get(i));
        }
        return out;
    }

    /** Whether any item would take another addon's details. */
    static boolean needed(Context context, AddonStore.Addon source, List<MetaItem> items) {
        for (MetaItem item : items) if (preferred(context, source, item) != null) return true;
        return false;
    }
}
