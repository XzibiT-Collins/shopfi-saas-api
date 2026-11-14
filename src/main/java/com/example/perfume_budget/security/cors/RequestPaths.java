package com.example.perfume_budget.security.cors;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

import java.util.Locale;

/**
 * ONE canonical request path for every security decision that depends on the URL zone (CORS zone, platform tenant
 * scope, platform token handling). {@code getRequestURI()} is the raw, undecoded string: the container and the
 * firewall let {@code %70latform} through while Spring MVC and Spring Security match the DECODED path, so a raw
 * prefix test and the router can disagree (DOMAIN-01b S1). Always use these helpers instead of the raw URI.
 */
public final class RequestPaths {

    public static final String PLATFORM_PREFIX = "/api/v1/platform";

    private RequestPaths() {
    }

    /** The decoded path within the application (percent-decoded, {@code ;} content and duplicate slashes removed). */
    public static String decoded(HttpServletRequest request) {
        String p = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return p == null ? "" : p;
    }

    /** {@link #decoded} lower-cased, for prefix comparisons that must be conservative. */
    public static String canonical(HttpServletRequest request) {
        return decoded(request).toLowerCase(Locale.ROOT);
    }

    /**
     * True when the request is under {@code /api/v1/platform} (compared decoded and lower-cased) but its raw path
     * within the application is not byte-identical to the decoded one: an encoded, semicolon or double-slash
     * variant. Such requests are refused with 400 before any other filter looks at them.
     */
    public static boolean isObfuscatedPlatformPath(HttpServletRequest request) {
        String decoded = decoded(request);
        if (!decoded.toLowerCase(Locale.ROOT).startsWith(PLATFORM_PREFIX)) {
            // an encoded form of the prefix whose decoded form is not platform cannot exist; nothing to guard
            return false;
        }
        return !rawPathWithinApplication(request).equals(decoded);
    }

    private static String rawPathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        return !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
