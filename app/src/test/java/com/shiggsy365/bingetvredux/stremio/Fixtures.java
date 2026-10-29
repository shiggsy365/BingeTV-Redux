package com.shiggsy365.bingetvredux.stremio;

import org.json.JSONObject;

/** Manifests shared by the tests (the same as Stremio Bridge's). */
final class Fixtures {
    static final String CINEMETA_LIKE = "{\"id\":\"com.example.meta\",\"version\":\"1.2.0\","
            + "\"name\":\"Example Meta\",\"description\":\"Test catalog addon\","
            + "\"resources\":[\"catalog\",\"meta\"],\"types\":[\"movie\",\"series\"],"
            + "\"idPrefixes\":[\"tt\"],\"catalogs\":["
            + "{\"type\":\"movie\",\"id\":\"top\",\"name\":\"Popular\",\"extra\":["
            + "{\"name\":\"genre\",\"options\":[\"Action\",\"Sci-Fi\"]},{\"name\":\"skip\"}]},"
            + "{\"type\":\"series\",\"id\":\"top\",\"name\":\"Popular\"},"
            + "{\"type\":\"movie\",\"id\":\"search\",\"name\":\"Search\","
            + "\"extra\":[{\"name\":\"search\",\"isRequired\":true}]}]}";

    static final String STREAM_ADDON = "{\"id\":\"com.example.streams\",\"version\":\"0.3.1\","
            + "\"name\":\"Example Streams\",\"resources\":[{\"name\":\"stream\","
            + "\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"tt\",\"kitsu:\"]}],"
            + "\"types\":[\"movie\",\"series\",\"anime\"],\"catalogs\":[],"
            + "\"behaviorHints\":{\"p2p\":true,\"configurable\":true}}";

    static Manifest manifest(String json) throws Exception {
        return Manifest.fromJson(new JSONObject(json));
    }

    private Fixtures() {}
}
