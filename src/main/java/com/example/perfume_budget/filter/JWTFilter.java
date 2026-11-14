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
import com.example.perfume_budget.exception.InvalidJWTTokenException;
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
import org.springframework.security.core.AuthenticationException;
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

        // Tenant requests. This filter NEVER writes an error response for a tenant auth cookie: it either
        // authenticates the request or leaves it anonymous. Whether anonymous is acceptable is decided by the
        // authorization rules (SecurityConfig): permitAll paths (login, logout, refresh, register, verify-otp,
        // forgot/reset, public catalog) proceed, protected paths get the structured JSON 401 from
        // JwtAuthenticationEntryPoint. An expired, malformed, other-tenant or platform cookie is therefore never a
        // plain-text 401 and never blocks login/logout/refresh for a user who also visited another shop (the
        // cookie lives on the single API host and is shared by every tenant).
        String token = extractTokenFromCookie(request);

        // No resolved tenant means a neutral allow-listed path (webhook, actuator, OAuth2...):
        // never authenticate from a cookie there, since that would load users from the
        // default schema. Tenant-only paths without a signal are rejected upstream.
        if (token != null && TenantContext.get() != null
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                authenticateTenantToken(request, token);
            } catch (JwtException | InvalidJWTTokenException | IllegalArgumentException | AuthenticationException e) {
                // Unusable cookie (expired, bad signature, malformed, no such user): anonymous. Deliberately not
                // clearing the cookie: when it is merely another tenant's, clearing would sign the user out of
                // that shop; a later login or logout on this tenant overwrites/clears it anyway.
                log.debug("Ignoring unusable auth cookie ({}); continuing anonymously", e.getClass().getSimpleName());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateTenantToken(HttpServletRequest request, String token) {
        // Cross-tenant defense: a token issued for tenant A (or the platform) must not authenticate against
        // tenant B. Checked before any tenant-local user lookup.
        if (!tenantMatches(token)) {
            log.debug("Auth cookie does not belong to the resolved tenant; treating the request as anonymous");
            return;
        }
        String username = jwtUtil.extractUsername(token);
        if (username == null) {
            return;
        }
        if (userDetailsService != null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (jwtUtil.validateToken(token, userDetails)) {
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
        // Decoded path, never the raw URI (DOMAIN-01b S1); the 400 guard filter already refuses obfuscated variants.
        String path = com.example.perfume_budget.security.cors.RequestPaths.decoded(request);
        if (PLATFORM_PUBLIC_AUTH_PATHS.contains(path) || path.startsWith(PLATFORM_ONBOARDING_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String token = PlatformAuthCookies.read(request, PlatformAuthCookies.ACCESS_COOKIE);
        if (token != null) {
            PlatformAdmin admin = platformJwtService.verify(token, PlatformJwtService.TYPE_ACCESS)
                    .flatMap(c -> platformAdminRepository.findByEmailIgnoreCase(c.getSubject()))
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
