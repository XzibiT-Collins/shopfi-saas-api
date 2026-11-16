package com.example.perfume_budget.filter;

import com.example.perfume_budget.model.SiteVisit;
import com.example.perfume_budget.repository.SiteVisitRepository;
import com.example.perfume_budget.security.ClientIps;
import com.example.perfume_budget.security.cors.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class SiteVisitFilter extends OncePerRequestFilter {
    private final SiteVisitRepository siteVisitRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String page = request.getRequestURI();
        String ip = resolveIpAddress(request);
        LocalDate today = LocalDate.now();

        if (!siteVisitRepository.existsByIpAddressAndVisitDateAndPage(ip, today,page)) {
            siteVisitRepository.save(SiteVisit.builder()
                    .ipAddress(ip)
                    .visitDate(today)
                    .page(page)
                    .build());
        }

        filterChain.doFilter(request, response);
    }

    /** Container-resolved client address (trusted-proxy aware); never a raw client supplied header. */
    private String resolveIpAddress(HttpServletRequest request) {
        return ClientIps.clientIp(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // the decoded, canonical path (DOMAIN-01b S1): the raw URI would let %70-style encodings slip past the tests
        String path = canonical(request);
        // HEAD is a probe/prefetch, not a visit (SEC-03b: HEAD became public on the storefront reads)
        return "HEAD".equalsIgnoreCase(request.getMethod()) ||
                hasInvalidPercentEncoding(request.getRequestURI()) ||
                isWebhookPath(request) ||
                path.startsWith("/actuator") ||
                path.startsWith("/api/v1/auth/me")||
                path.endsWith(".js") ||
                path.endsWith(".css") ||
                path.endsWith(".ico");
    }

    /**
     * API-04: {@code /api/v1/webhook/{token}} carries the tenant's secret webhook token in the path, so it is never
     * stored as a "page" (nor is it a visit). Rework 2: decided on {@link RequestPaths#canonical} (percent-decoded,
     * {@code ;} content and duplicate slashes removed, lower-cased), and conservatively also on the raw URI and on
     * further decodings of the path, so no spelling ({@code %77ebhook}, {@code %2577ebhook}, {@code //},
     * {@code ;params}, upper case) gets the token into {@code site_visits}.
     */
    static boolean isWebhookPath(HttpServletRequest request) {
        if (isWebhookPath(canonical(request))) {
            return true;
        }
        return isWebhookPath(request.getRequestURI());
    }

    /** Canonical path; a malformed percent-encoding (not decodable) falls back to the lower-cased raw URI. */
    private static String canonical(HttpServletRequest request) {
        try {
            return RequestPaths.canonical(request);
        } catch (IllegalArgumentException malformed) {
            String raw = request.getRequestURI();
            return raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final int MAX_DECODE_ROUNDS = 3;

    /**
     * EVT-01 (API-04 review Q6): a path whose percent-encoding is not strictly valid, in any of the first
     * {@link #MAX_DECODE_ROUNDS} decoding rounds, is unrecordable. Invalid means a malformed escape ({@code %zz},
     * {@code %4}, a trailing {@code %}) or bytes that are not well-formed UTF-8: overlong forms ({@code %C1%A2} for
     * {@code b}, {@code %C0%AF} for {@code /}), surrogates, stray continuation / {@code %FF} bytes, truncated sequences.
     * The lenient decoder used for routing turns such bytes into U+FFFD, so {@code /api/v1/we%C1%A2hook/<token>} no
     * longer looked like the webhook and its token was stored; no legitimate storefront page needs such a spelling.
     */
    static boolean hasInvalidPercentEncoding(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String p = rawPath;
        for (int round = 0; round < MAX_DECODE_ROUNDS && p.indexOf('%') >= 0; round++) {
            p = strictDecode(p);
            if (p == null) {
                return true;
            }
        }
        return false;
    }

    /** Percent-decodes {@code s} as strict UTF-8; {@code null} when an escape is malformed or the bytes are not UTF-8. */
    private static String strictDecode(String s) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write((hi << 4) | lo);
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                byte[] enc = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(enc, 0, enc.length);
                i += Character.charCount(cp);
            }
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            return null;
        }
    }

    static boolean isWebhookPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path;
        for (int round = 0; ; round++) {
            String n = p.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/").toLowerCase(java.util.Locale.ROOT);
            if (n.equals("/api/v1/webhook") || n.startsWith("/api/v1/webhook/")) {
                return true;
            }
            if (round == MAX_DECODE_ROUNDS || !p.contains("%")) {
                return false;
            }
            try {
                p = org.springframework.web.util.UriUtils.decode(p, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }
    }
}
