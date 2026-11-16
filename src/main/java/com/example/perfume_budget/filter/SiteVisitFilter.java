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
