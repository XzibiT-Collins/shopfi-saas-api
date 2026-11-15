package com.example.perfume_budget.filter;

import com.example.perfume_budget.model.SiteVisit;
import com.example.perfume_budget.repository.SiteVisitRepository;
import com.example.perfume_budget.security.ClientIps;
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
        String path = request.getRequestURI();
        // HEAD is a probe/prefetch, not a visit (SEC-03b: HEAD became public on the storefront reads)
        return "HEAD".equalsIgnoreCase(request.getMethod()) ||
                isWebhookPath(path) ||
                path.startsWith("/actuator") ||
                path.startsWith("/api/v1/auth/me")||
                path.endsWith(".js") ||
                path.endsWith(".css") ||
                path.endsWith(".ico");
    }

    /**
     * API-04 rework: {@code /api/v1/webhook/{token}} carries the tenant's secret webhook token in the path, so it is
     * never stored as a "page" (nor is it a visit). Matched after collapsing repeated slashes and dropping path
     * parameters ({@code ;...}) and case, so no spelling of the URL slips through.
     */
    static boolean isWebhookPath(String requestUri) {
        if (requestUri == null) {
            return false;
        }
        String p = requestUri.replaceAll("/{2,}", "/").toLowerCase(java.util.Locale.ROOT);
        p = p.replaceAll(";[^/]*", "");
        return p.equals("/api/v1/webhook") || p.startsWith("/api/v1/webhook/");
    }
}
