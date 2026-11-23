package com.remory.app;

import java.net.URI;

/** Only this exact origin may stay in the WebView or reach its native bridge. */
final class SitePolicy {
    static boolean isSameOrigin(String base, String candidate) {
        try {
            URI a = URI.create(base);
            URI b = URI.create(candidate);
            return a.getHost() != null && b.getHost() != null && b.getUserInfo() == null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }

    static String origin(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
