package com.coolcupman.webserv;

import android.net.Uri;

import java.util.Locale;

/** Turns what the user typed (IP, hostname, host:port, IPv6, full URL) into a loadable URL. */
public final class UrlUtil {

    public enum SchemeMode { AUTO, HTTPS, HTTP }

    /** Result of normalising user input. */
    public static final class Target {
        public final String url;
        /** True when no scheme was typed and we picked one; enables the https -> http fallback. */
        public final boolean schemeGuessed;

        Target(String url, boolean schemeGuessed) {
            this.url = url;
            this.schemeGuessed = schemeGuessed;
        }
    }

    private UrlUtil() {}

    /**
     * Normalises input. Returns null if no usable host can be extracted.
     * Examples: "192.168.1.1" -> https://192.168.1.1/ (guessed), "router.lan:8080" -> https://router.lan:8080/,
     * "fe80::1" -> https://[fe80::1]/, "http://nas:5000/x" -> unchanged.
     */
    public static Target normalize(String input, SchemeMode mode) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        s = s.replace(" ", "");

        String lower = s.toLowerCase(Locale.ROOT);
        boolean hasScheme = lower.startsWith("http://") || lower.startsWith("https://");
        boolean guessed = false;

        if (hasScheme) {
            int sep = s.indexOf("://");
            s = s.substring(0, sep).toLowerCase(Locale.ROOT) + s.substring(sep);
            if (mode == SchemeMode.HTTPS && lower.startsWith("http://")) {
                s = "https://" + s.substring(7);
            } else if (mode == SchemeMode.HTTP && lower.startsWith("https://")) {
                s = "http://" + s.substring(8);
            }
        } else {
            if (lower.contains("://")) return null; // ftp://, ssh:// etc. are not web GUIs
            s = wrapBareIpv6(s);
            String scheme;
            switch (mode) {
                case HTTP: scheme = "http"; break;
                case HTTPS: scheme = "https"; break;
                default:
                    scheme = defaultSchemeForPort(portOf(s));
                    guessed = true;
                    break;
            }
            s = scheme + "://" + s;
        }

        Uri uri = Uri.parse(s);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        if (uri.getPath() == null || uri.getPath().isEmpty()) {
            s = uri.buildUpon().path("/").build().toString();
        }
        return new Target(s, guessed);
    }

    /** Same URL with the other scheme, keeping explicit ports. */
    public static String withScheme(String url, String scheme) {
        Uri uri = Uri.parse(url);
        return uri.buildUpon().scheme(scheme).build().toString();
    }

    /** "host[:port]" used as the key for trusted certificates, saved credentials and per-host settings. */
    public static String hostKey(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) return "";
        int port = uri.getPort();
        if (port == -1) port = "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Short display form, e.g. "192.168.1.1:8443". */
    public static String displayHost(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) return url;
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    /** Ports where plain HTTP is the usual default; everything else tries HTTPS first. */
    static String defaultSchemeForPort(int port) {
        switch (port) {
            case 80: case 81: case 3000: case 5000: case 5601: case 8000: case 8080: case 8081:
            case 8088: case 8123: case 8384: case 8888: case 9000: case 9200: case 15672:
            case 28017: case 32400:
                return "http";
            default:
                return "https";
        }
    }

    /** Port from "host:port/..." or "[v6]:port/..." without a scheme; -1 when absent. */
    static int portOf(String noScheme) {
        String hostPort = noScheme;
        int slash = hostPort.indexOf('/');
        if (slash >= 0) hostPort = hostPort.substring(0, slash);
        int colon;
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            if (end < 0) return -1;
            colon = hostPort.indexOf(':', end);
        } else {
            colon = hostPort.lastIndexOf(':');
        }
        if (colon < 0) return -1;
        try {
            return Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "fe80::1" -> "[fe80::1]"; leaves "[fe80::1]:443" and "host:port" alone. */
    static String wrapBareIpv6(String noScheme) {
        if (noScheme.startsWith("[")) return noScheme;
        int slash = noScheme.indexOf('/');
        String hostPart = slash >= 0 ? noScheme.substring(0, slash) : noScheme;
        String rest = slash >= 0 ? noScheme.substring(slash) : "";
        int colons = 0;
        for (int i = 0; i < hostPart.length(); i++) if (hostPart.charAt(i) == ':') colons++;
        if (colons >= 2) return "[" + hostPart + "]" + rest;
        return noScheme;
    }
}
