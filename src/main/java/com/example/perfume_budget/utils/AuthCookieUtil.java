package com.example.perfume_budget.utils;

import com.example.perfume_budget.exception.InactiveAccountException;
import com.example.perfume_budget.exception.InvalidJWTTokenException;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AuthCookieUtil {
    private static final String INVALID_REFRESH = "Invalid or expired token";

    private final JWTUtil jwtUtil;
    private final UserRepository userRepository;

    @Value("${application.security.cookie.secure:true}")
    private boolean secure;

    @Value("${application.security.cookie.same-site:None}")
    private String sameSite;

    public void setAuthCookies(HttpServletRequest request, HttpServletResponse response, User user){
        // Set tokens as HttpOnly cookies
        addCookie(response, "accessToken", generateAccessToken(user), jwtUtil.accessTokenTtlSeconds());
        addCookie(response, "refreshToken", generateRefreshToken(user), jwtUtil.refreshTokenTtlSeconds());
    }

    public void removeAuthCookies(HttpServletResponse response){
        addCookie(response, "accessToken", "", 0);
        addCookie(response, "refreshToken", "", 0);
    }

    public String generateAccessToken(User user){
        // Generate JWT tokens
        return jwtUtil.generateAccessToken(
                user.getEmail(),
                user.getRole(),
                user.getId(),
                user.getFullName()
        );
    }

    public String generateRefreshToken(User user){
        return jwtUtil.generateRefreshToken(
                user.getEmail(),
                user.getRole(),
                user.getId(),
                user.getFullName()
        );
    }

    private void addCookie(HttpServletResponse response, String name, String token, int maxAge) {
        // HttpOnly so tokens are never exposed to JS; no domain attribute (bound to the single
        // API host). The cookie is shared across tenants on that host, so cross-tenant isolation
        // is enforced by the JWT tenant-claim check in JWTFilter, not by the cookie. Cross-site
        // frontend->API requires SameSite=None; Secure in prod (see cookie.* config).
        ResponseCookie cookie = ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite(sameSite)
                .maxAge(maxAge)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void refreshAccessToken(String refreshToken, HttpServletResponse response){
        // Validate signature and tenant binding BEFORE touching the (tenant-scoped) user table,
        // so a refresh token cannot be replayed against another tenant's schema.
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidJWTTokenException(INVALID_REFRESH);
        }
        jwtUtil.validateToken(refreshToken);
        String contextSlug = TenantContext.getSlug();
        // Fail closed: a refresh token is only valid for the tenant resolved for this request. The message is the
        // same generic one as for a bad signature so a token from another tenant (or the platform) is simply invalid.
        if (contextSlug == null || TenantContext.PLATFORM_SLUG.equals(contextSlug)
                || !contextSlug.equals(jwtUtil.extractTenant(refreshToken))) {
            throw new InvalidJWTTokenException(INVALID_REFRESH);
        }

        String email = jwtUtil.extractUsername(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        if(!user.isActive()){
            throw new InactiveAccountException("User account is inactive");
        }
        String newAccessToken = generateAccessToken(user);
        addCookie(response, "accessToken", newAccessToken, jwtUtil.accessTokenTtlSeconds());
    }
}
