package com.shiggsy365.bingetvredux.stremio;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Addon URLs in the Stremio addon protocol. */
public final class StremioUrls {
    public static final String MANIFEST = "manifest.json";

    private StremioUrls() {}

    /**
     * Turns what the user typed into a canonical {@code https://…/manifest.json} transport URL.
     * Accepts {@code stremio://} links and base URLs without {@code /manifest.json}. The path is
     * otherwise kept as it is: configured addons keep their settings (often tokens) in it.
     */
    public static String normalize(String url) throws StremioException {
        url = url == null ? "" : url.trim();
        if (url.isEmpty()) throw new StremioException("Enter the addon's manifest URL");
        if (url.startsWith("stremio://")) {
            url = "https://" + url.substring("stremio://".length());
        } else if (!url.contains("://")) {
            url = "https://" + url;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new StremioException("Not a valid addon URL: " + url);
        }
        String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getRawAuthority() == null) {
            throw new StremioException("Not a valid addon URL: " + url);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!path.endsWith("/" + MANIFEST)) path = stripTrailingSlashes(path) + "/" + MANIFEST;
        String query = uri.getRawQuery();
        return scheme + "://" + uri.getRawAuthority() + path + (query != null ? "?" + query : "");
    }

    /** The addon root that resource paths are added to. */
    public static String baseUrl(String transportUrl) {
        String withoutQuery = transportUrl;
        int q = withoutQuery.indexOf('?');
        if (q >= 0) withoutQuery = withoutQuery.substring(0, q);
        int hash = withoutQuery.indexOf('#');
        if (hash >= 0) withoutQuery = withoutQuery.substring(0, hash);
        if (withoutQuery.endsWith("/" + MANIFEST)) {
            withoutQuery = withoutQuery.substring(0, withoutQuery.length() - MANIFEST.length() - 1);
        }
        return stripTrailingSlashes(withoutQuery);
    }

    /** The addon's configure page, for addons whose manifest says they're configurable. */
    public static String configureUrl(String transportUrl) {
        return baseUrl(transportUrl) + "/configure";
    }

    /**
     * {@code {base}/{resource}/{type}/{id}[/{extra}].json} with Stremio's encoding. Like Stremio,
     * the manifest URL's query string (e.g. a cache-buster such as {@code ?bcv=48}) is kept on
     * every resource request. Extras with null or empty values are left out.
     */
    public static String resourceUrl(String transportUrl, String resource, String type,
            String id, List<Map.Entry<String, String>> extra) {
        StringBuilder url = new StringBuilder(baseUrl(transportUrl))
                .append('/').append(encode(resource))
                .append('/').append(encode(type))
                .append('/').append(encode(id));
        if (extra != null) {
            boolean first = true;
            for (Map.Entry<String, String> e : extra) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                url.append(first ? '/' : '&').append(encode(e.getKey())).append('=')
                        .append(encode(e.getValue()));
                first = false;
            }
        }
        url.append(".json");
        int q = transportUrl.indexOf('?');
        if (q >= 0) url.append(transportUrl.substring(q));
        return url.toString();
    }

    /** JavaScript's encodeURIComponent, which Stremio uses. */
    public static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-_.!~*'()".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }

    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }
}
