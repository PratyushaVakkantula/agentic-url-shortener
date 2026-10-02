package com.agentic.shortener.service;

import com.agentic.shortener.domain.InvalidLinkRequestException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides whether a URL may be shortened (NFR-1). A shortener lends its domain's reputation
 * to whatever it points at, so it must not become a launder for script URLs, phishing
 * tricks, or links into private networks.
 *
 * <p>Checks, in order: length, syntax, scheme allow-list, embedded credentials, then host.
 * IP-literal hosts are parsed the way browsers and {@code inet_aton} do, so alternate
 * spellings such as {@code 127.1}, {@code 2130706433} or {@code 0x7f.0.0.1} are caught.
 *
 * <p><b>Never performs DNS lookups.</b> We never fetch the target (A-6), so a public hostname
 * that resolves to a private address is only a risk to the clicking user's own network; that
 * DNS-rebinding class is documented as a limitation rather than "solved" with a racy
 * resolve-then-check.
 */
@Component
public class UrlSafetyValidator {

    public static final int MAX_LENGTH = 2048;

    private static final String FIELD = "url";
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern NUMERIC_LABEL = Pattern.compile("0x[0-9a-f]*|[0-9]+");
    /** Suffixes reserved for local/internal name resolution (RFC 6761, RFC 6762, common practice). */
    private static final List<String> INTERNAL_SUFFIXES = List.of(".localhost", ".local", ".internal", ".lan", ".home.arpa");

    /** Non-public IPv4 ranges: [network, prefixLength]. */
    private static final int[][] BLOCKED_V4 = {
            {ip(0, 0, 0, 0), 8},        // "this" network
            {ip(10, 0, 0, 0), 8},       // private
            {ip(100, 64, 0, 0), 10},    // carrier-grade NAT
            {ip(127, 0, 0, 0), 8},      // loopback
            {ip(169, 254, 0, 0), 16},   // link-local (incl. cloud metadata 169.254.169.254)
            {ip(172, 16, 0, 0), 12},    // private
            {ip(192, 0, 0, 0), 24},     // IETF protocol assignments
            {ip(192, 0, 2, 0), 24},     // TEST-NET-1
            {ip(192, 168, 0, 0), 16},   // private
            {ip(198, 18, 0, 0), 15},    // benchmarking
            {ip(198, 51, 100, 0), 24},  // TEST-NET-2
            {ip(203, 0, 113, 0), 24},   // TEST-NET-3
            {ip(224, 0, 0, 0), 4},      // multicast
            {ip(240, 0, 0, 0), 4},      // reserved + broadcast
    };

    /**
     * @return the URL to store (trimmed of surrounding whitespace)
     * @throws InvalidLinkRequestException with a specific error code when the URL is rejected
     */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw reject("INVALID_URL", "URL must not be blank.");
        }
        String url = raw.strip();
        if (url.length() > MAX_LENGTH) {
            throw reject("URL_TOO_LONG", "URL must be at most " + MAX_LENGTH + " characters.");
        }
        if (url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw reject("INVALID_URL", "URL must not contain whitespace or control characters.");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw reject("INVALID_URL", "URL is not syntactically valid.");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw reject("UNSUPPORTED_SCHEME", "Only http and https URLs can be shortened.");
        }
        // "https://trusted.com@evil.com" displays a trusted name but goes to evil.com.
        if (uri.getRawUserInfo() != null) {
            throw reject("CREDENTIALS_IN_URL", "URLs with embedded user info are not allowed.");
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            // Also covers non-ASCII (IDN) hosts, which java.net.URI does not expose; see limitations.
            throw reject("INVALID_URL", "URL must include a valid host name.");
        }
        checkHost(normalizeHost(host));
        return url;
    }

    private void checkHost(String host) {
        if (host.startsWith("[")) {
            checkIpv6Literal(host.substring(1, host.length() - 1));
            return;
        }
        if (looksLikeIpv4(host)) {
            Integer address = parseIpv4(host);
            if (address == null) {
                throw reject("INVALID_URL", "URL host is a malformed IP address.");
            }
            if (isBlockedV4(address)) {
                throw blockedHost();
            }
            return;
        }
        if (host.equals("localhost") || INTERNAL_SUFFIXES.stream().anyMatch(host::endsWith)) {
            throw blockedHost();
        }
        // Single-label names ("http://intranet/") only resolve inside private networks.
        if (!host.contains(".")) {
            throw blockedHost();
        }
    }

    private void checkIpv6Literal(String literal) {
        InetAddress address;
        try {
            // A literal (contains ':') is parsed locally; no DNS lookup takes place.
            address = InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw reject("INVALID_URL", "URL host is a malformed IP address.");
        }
        // IPv4-mapped addresses (::ffff:127.0.0.1) come back as Inet4Address.
        if (address instanceof Inet4Address v4) {
            if (isBlockedV4(toInt(v4.getAddress()))) {
                throw blockedHost();
            }
            return;
        }
        byte[] b = address.getAddress();
        boolean uniqueLocal = (b[0] & 0xfe) == 0xfc;                                 // fc00::/7
        boolean documentation = b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8; // 2001:db8::/32
        boolean nat64 = b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b;     // 64:ff9b::/96
        boolean blocked = address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress() || uniqueLocal || documentation
                || (nat64 && isBlockedV4(toInt(new byte[] {b[12], b[13], b[14], b[15]})))
                || (isIpv4Compatible(b) && isBlockedV4(toInt(new byte[] {b[12], b[13], b[14], b[15]})));
        if (blocked) {
            throw blockedHost();
        }
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        // "localhost." is the fully-qualified form of "localhost".
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    private static boolean looksLikeIpv4(String host) {
        String[] labels = host.split("\\.", -1);
        if (labels.length > 4) {
            return false;
        }
        for (String label : labels) {
            if (!NUMERIC_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code inet_aton} semantics: 1–4 parts, each decimal, octal (leading 0) or hex (0x);
     * the last part fills all remaining bytes. Returns null when malformed or out of range.
     */
    static Integer parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long value = parsePart(parts[i]);
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        long result = 0;
        for (int i = 0; i < values.length - 1; i++) {
            if (values[i] > 255) {
                return null;
            }
            result |= values[i] << (8 * (3 - i));
        }
        long last = values[values.length - 1];
        long maxLast = (1L << (8 * (4 - (values.length - 1)))) - 1;
        if (last > maxLast) {
            return null;
        }
        return (int) (result | last);
    }

    private static Long parsePart(String part) {
        try {
            if (part.startsWith("0x")) {
                return part.length() == 2 ? 0L : Long.parseLong(part.substring(2), 16);
            }
            if (part.length() > 1 && part.startsWith("0")) {
                return Long.parseLong(part.substring(1), 8);
            }
            return part.isEmpty() ? null : Long.parseLong(part);
        } catch (NumberFormatException e) {
            return null; // includes overflow and digits 8/9 in octal
        }
    }

    private static boolean isBlockedV4(int address) {
        for (int[] range : BLOCKED_V4) {
            int mask = range[1] == 0 ? 0 : -1 << (32 - range[1]);
            if ((address & mask) == (range[0] & mask)) {
                return true;
            }
        }
        return false;
    }

    /** Deprecated IPv4-compatible form ::a.b.c.d (first 12 bytes zero). */
    private static boolean isIpv4Compatible(byte[] b) {
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static int toInt(byte[] b) {
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    private static int ip(int a, int b, int c, int d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static InvalidLinkRequestException blockedHost() {
        return reject("BLOCKED_HOST", "URLs pointing to local, private or reserved network addresses are not allowed.");
    }

    private static InvalidLinkRequestException reject(String code, String message) {
        return new InvalidLinkRequestException(code, FIELD, message);
    }
}
