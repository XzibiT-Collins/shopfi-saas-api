package com.example.perfume_budget.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * Fail-closed gate that runs right after {@link TenantResolutionFilter}. If no tenant is bound
 * (no Origin/Referer/dev header, or an unknown dev header) and the path is not on the neutral
 * allow-list ({@link TenantResolutionFilter#isNeutralPath}), the request is rejected before
 * SiteVisitFilter, JWTFilter or any controller can touch a tenant schema (which would otherwise
 * fall back to {@code tenant_template}).
 *
 * <p>A request carrying an auth cookie that cannot be bound to a tenant is answered 401;
 * everything else 400. Deliberately not a Spring bean: it is added to the security chain
 * explicitly, so it can never be registered twice or skipped.
 */
@Slf4j
public class TenantRequiredFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (TenantContext.get() == null && !TenantResolutionFilter.isNeutralPath(request.getRequestURI())) {
            log.warn("Rejecting {} {}: no tenant signal", request.getMethod(), request.getRequestURI());
            boolean hasAuthCookie = request.getCookies() != null && Arrays.stream(request.getCookies())
                    .anyMatch(c -> "accessToken".equals(c.getName()) || "refreshToken".equals(c.getName()));
            response.sendError(hasAuthCookie ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_BAD_REQUEST,
                    "Tenant could not be determined");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
