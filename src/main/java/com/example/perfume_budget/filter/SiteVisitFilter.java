package com.example.perfume_budget.filter;

import com.example.perfume_budget.model.SiteVisit;
import com.example.perfume_budget.repository.SiteVisitRepository;
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

    private String resolveIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            log.info("Site Visit Tracked");
            return ip.split(",")[0].trim(); // take first IP if multiple proxies
        }
        return request.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") ||
                path.startsWith("/api/v1/auth/me")||
                path.endsWith(".js") ||
                path.endsWith(".css") ||
                path.endsWith(".ico");
    }
}