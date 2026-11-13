package com.example.perfume_budget.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The single place that turns a request into a client IP for rate limiting and visit tracking.
 *
 * <p>It deliberately reads <b>only</b> {@link HttpServletRequest#getRemoteAddr()} and never looks at
 * {@code X-Forwarded-For}, {@code X-Real-IP} or {@code Forwarded} itself. Behind a reverse proxy
 * {@code getRemoteAddr()} is already the right value because {@code server.forward-headers-strategy=native} lets
 * Tomcat's RemoteIpValve resolve it: it walks {@code X-Forwarded-For} from the RIGHT, skips hops that match
 * {@code server.tomcat.remoteip.internal-proxies} (our trusted proxies) and takes the first untrusted hop, i.e. the
 * address our own proxy observed. Everything to the left of that hop is client supplied and is ignored. If the
 * direct peer is not a trusted proxy, the headers are ignored altogether. (The previous setting,
 * {@code framework}, used Spring's ForwardedHeaderFilter which takes the LEFTMOST, client controlled, entry.)
 */
public final class ClientIps {

    private ClientIps() {
    }

    /** The resolved client address as the servlet container reports it (never null). */
    public static String clientIp(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr == null || addr.isBlank() ? "unknown" : addr.strip();
    }

    /** Rate-limit bucket key: IPv4 as is, IPv6 reduced to its /64 prefix (one subscriber owns a whole /64). */
    public static String rateLimitKey(HttpServletRequest request) {
        return bucketKey(clientIp(request));
    }

    static String bucketKey(String ip) {
        if (ip.indexOf(':') < 0) {
            return ip; // IPv4 (or "unknown")
        }
        String literal = ip;
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        // Only ever parse IP literals (no DNS lookups): hex digits, ':' and '.' only.
        if (!literal.matches("[0-9a-fA-F:.]+")) {
            return ip.toLowerCase(Locale.ROOT);
        }
        try {
            InetAddress a = InetAddress.getByName(literal);
            if (!(a instanceof Inet6Address)) {
                return a.getHostAddress(); // ::ffff:1.2.3.4 is mapped to the IPv4 address by the JDK
            }
            byte[] b = a.getAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                sb.append(String.format("%02x%02x", b[i], b[i + 1])).append(i < 6 ? ":" : "");
            }
            return sb.append("::/64").toString();
        } catch (UnknownHostException e) {
            return ip.toLowerCase(Locale.ROOT);
        }
    }
}
