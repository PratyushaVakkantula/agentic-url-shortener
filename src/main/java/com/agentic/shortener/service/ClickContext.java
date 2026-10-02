package com.agentic.shortener.service;

import java.net.URI;
import java.util.Locale;

/**
 * Reduces raw request headers to the privacy-minimal values we store (NFR-6). Raw headers are
 * never persisted or logged.
 */
public final class ClickContext {

    private ClickContext() {
    }

    /** Host of the Referer header, or null when absent or unparseable. Path and query are dropped. */
    public static String referrerHost(String referer) {
        if (referer == null || referer.isBlank() || referer.length() > 2048) {
            return null;
        }
        try {
            String host = URI.create(referer.strip()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.length() > 255 ? host.substring(0, 255) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Coarse browser family. Order matters: Edge and Opera include "Chrome", and Chrome includes
     * "Safari", so the more specific tokens are checked first.
     */
    public static String userAgentFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")) {
            return "Bot";
        }
        if (ua.contains("edg/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        if (ua.startsWith("curl/") || ua.startsWith("wget/") || ua.contains("httpie")) {
            return "CLI";
        }
        return "Other";
    }
}
