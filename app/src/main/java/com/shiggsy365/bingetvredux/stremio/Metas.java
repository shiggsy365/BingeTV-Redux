package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Finding an item's full meta: its details and, for a show, its episodes. */
public final class Metas {
    public static final String CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json";
    public static final long TTL_MS = 6 * 3600_000L;

    private Metas() {}

    /** A meta and the addon that sent it. */
    public static final class Found {
        public final MetaItem meta;
        public final String addonName;

        Found(MetaItem meta, String addonName) {
            this.meta = meta;
            this.addonName = addonName;
        }
    }

    /**
     * Asks each addon that has meta for this type and id, in order, then Cinemeta (which covers
     * IMDb films and series even when it isn't installed). Addons that fail or have nothing are
     * skipped.
     */
    public static Found fetch(StremioClient client, AddonStore store, String type, String id)
            throws StremioException {
        List<String[]> candidates = new ArrayList<>();
        for (AddonStore.Addon a : store.addonsFor("meta", type, id)) {
            candidates.add(new String[]{a.manifest.name, a.transportUrl});
        }
        boolean askedCinemeta = false;
        for (String[] c : candidates) if (c[1].equals(CINEMETA_URL)) askedCinemeta = true;
        if (!askedCinemeta && (type.equals("movie") || type.equals("series"))
                && id.startsWith("tt")) {
            candidates.add(new String[]{"Cinemeta", CINEMETA_URL});
        }
        List<String> errors = new ArrayList<>();
        for (String[] c : candidates) {
            try {
                JSONObject data = client.resource(c[1], "meta", type, id, null, TTL_MS);
                MetaItem meta = MetaItem.full(data.optJSONObject("meta"), type);
                if (meta != null) return new Found(meta, c[0]);
                errors.add(c[0] + ": nothing");
            } catch (StremioException e) {
                errors.add(c[0] + ": " + e.getMessage());
            }
        }
        throw new StremioException("No details found for this title"
                + (errors.isEmpty() ? "" : " (" + String.join("; ", errors) + ")"));
    }
}
