package com.example.perfume_budget.filter;



import com.example.perfume_budget.dto.AppUser;
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

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = extractTokenFromCookie(request);

        try {
            if (token != null) {
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
        if (identity == null || identity.scope() == TenantScope.PLATFORM) {
            return true;
        }
        String tokenTenant = jwtUtil.extractTenant(token);
        return identity.slug() != null && identity.slug().equals(tokenTenant);
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
