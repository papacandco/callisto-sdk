package com.callisto.sdk.http;

import java.net.URI;
import java.util.Locale;

/**
 * The HTTPS rule for URLs that carry a secret (the Logs endpoint, the error DSN). Internal to the
 * SDK; public only because the logs and reporting packages share it.
 */
public final class SecureUrl {

    private SecureUrl() {
    }

    /**
     * Whether {@code url} is an absolute {@code https} URL, or an {@code http} URL whose host is
     * {@code localhost}, {@code 127.0.0.1}, {@code ::1} or ends with {@code .localhost} (local
     * development). Anything else would send the secret in clear text.
     */
    public static boolean isSecure(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!uri.isAbsolute() || scheme == null || host == null || host.isEmpty()) {
                return false;
            }
            if ("https".equalsIgnoreCase(scheme)) {
                return true;
            }
            if (!"http".equalsIgnoreCase(scheme)) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                    || host.equals("0:0:0:0:0:0:0:1") || host.endsWith(".localhost");
        } catch (Exception e) {
            return false;
        }
    }
}
