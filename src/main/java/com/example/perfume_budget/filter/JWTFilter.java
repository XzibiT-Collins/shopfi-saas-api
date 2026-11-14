package com.example.perfume_budget.filter;



import com.example.perfume_budget.dto.AppUser;
import com.example.perfume_budget.platform.auth.PlatformAuthCookies;
import com.example.perfume_budget.platform.auth.PlatformJwtService;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.enums.PlatformRole;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.tenancy.TenantScope;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Slf4j
@Component
@RequiredArgsConstructor
public class JWTFilter extends OncePerRequestFilter {

    private final JWTUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final PlatformJwtService platformJwtService;
    private final PlatformAdminRepository platformAdminRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        TenantIdentity identity = TenantContext.get();
        if (identity != null && identity.scope() == TenantScope.PLATFORM) {
            // Platform control plane: tenant cookies/tokens are never read here and platform cookies are never
            // read on tenant paths (below), so the two token families cannot be confused.
            authenticatePlatform(request, response, filterChain);
            return;
        }

        String token = extractTokenFromCookie(request);

        try {
            // No resolved tenant means a neutral allow-listed path (webhook, actuator, OAuth2...):
            // never authenticate from a cookie there, since that would load users from the
            // default schema. Tenant-only paths without a signal are rejected upstream.
            if (token != null && TenantContext.get() != null) {
                // Cross-tenant defense: a token issued for tenant A must not authenticate
                // against tenant B. Skip for neutral/platform-scoped requests.
                if (!tenantMatches(token)) {
                    log.warn("Rejecting request: token tenant does not match resolved tenant context");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("Tenant mismatch");
                    return;
                }

                String username = jwtUtil.extractUsername(token);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                    if (userDetailsService != null) {
                        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                        if (jwtUtil.validateToken(token, userDetails)) {
                            log.info("User {} is authenticated", username);
                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    } else {
                        jwtUtil.validateToken(token);
                        Claims claims = jwtUtil.parseToken(token);
                        Long userId = jwtUtil.extractUserId(token);
                        String role = jwtUtil.extractRole(token);
                        String userEmail = claims.getSubject();
                        String fullName = claims.get("fullName", String.class);

                        AppUser user = new AppUser(userId, role, userEmail, fullName);
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            }
            filterChain.doFilter(request, response);
        } catch (JwtException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid or expired token");
        }
    }

    /**
     * True when the token may proceed: neutral (no tenant resolved) and platform-scoped
     * requests skip the check; tenant-scoped requests require the token's {@code tenant}
     * claim to equal the resolved tenant slug.
     */
    private boolean tenantMatches(String token) {
        TenantIdentity identity = TenantContext.get();
        if (identity == null) {
            return false;
        }
        // Fail closed: platform scope never reaches this method (handled by authenticatePlatform), and a
        // platform-claim token is never valid for a tenant.
        if (identity.scope() == TenantScope.PLATFORM) {
            return false;
        }
        String tokenTenant = jwtUtil.extractTenant(token);
        if (TenantContext.PLATFORM_SLUG.equals(tokenTenant)) {
            return false;
        }
        return identity.slug() != null && identity.slug().equals(tokenTenant);
    }

    private static final java.util.Set<String> PLATFORM_PUBLIC_AUTH_PATHS = java.util.Set.of(
            "/api/v1/platform/auth/login", "/api/v1/platform/auth/refresh", "/api/v1/platform/auth/logout");
    private static final String PLATFORM_ONBOARDING_PREFIX = "/api/v1/platform/onboarding/";

    /**
     * Authenticates {@code /api/v1/platform/**} from the {@code platformAccessToken} cookie only: signature (key
     * derived for platform), {@code tenant="__platform__"}, role SUPER_ADMIN, type access, and an ACTIVE
     * {@link PlatformAdmin} in master. {@code /platform/auth/{login,refresh,logout}} and {@code /platform/onboarding/**} are public and
     * skip token processing entirely so a stale cookie can never block login or signup.
     */
    private void authenticatePlatform(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (PLATFORM_PUBLIC_AUTH_PATHS.contains(path) || path.startsWith(PLATFORM_ONBOARDING_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String token = PlatformAuthCookies.read(request, PlatformAuthCookies.ACCESS_COOKIE);
        if (token != null) {
            PlatformAdmin admin = platformJwtService.verify(token, PlatformJwtService.TYPE_ACCESS)
                    .flatMap(c -> platformAdminRepository.findByEmail(c.getSubject()))
                    .filter(PlatformAdmin::isActive)
                    .orElse(null);
            if (admin == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"description\":\"Invalid or expired token\",\"data\":null}");
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(
                    new PlatformPrincipal(admin.getId(), admin.getEmail(), admin.getFullName()), null,
                    java.util.List.of(new SimpleGrantedAuthority("ROLE_" + PlatformRole.SUPER_ADMIN.name())));
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(cookie -> "accessToken".equals(cookie.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }
}
