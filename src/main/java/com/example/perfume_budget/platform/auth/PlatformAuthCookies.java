package com.example.perfume_budget.platform.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Platform auth cookies. The API is a single host shared with the tenant cookies ({@code accessToken} /
 * {@code refreshToken}, path {@code /}), so these use DISTINCT names and narrower paths: they never overwrite or
 * clear a tenant cookie and the tenant code never reads them. The refresh cookie is only sent to
 * {@code /api/v1/platform/auth}.
 */
@Component
public class PlatformAuthCookies {

    public static final String ACCESS_COOKIE = "platformAccessToken";
    public static final String REFRESH_COOKIE = "platformRefreshToken";
    public static final String ACCESS_PATH = "/api/v1/platform";
    public static final String REFRESH_PATH = "/api/v1/platform/auth";

    private final PlatformAuthProperties properties;
    private final boolean secure;
    private final String sameSite;

    public PlatformAuthCookies(PlatformAuthProperties properties,
                               @Value("${application.security.cookie.secure:true}") boolean secure,
                               @Value("${application.security.cookie.same-site:None}") String sameSite) {
        this.properties = properties;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public void setAccess(HttpServletResponse response, String token) {
        add(response, ACCESS_COOKIE, token, ACCESS_PATH, properties.getAccessTtl());
    }

    public void setRefresh(HttpServletResponse response, String token) {
        add(response, REFRESH_COOKIE, token, REFRESH_PATH, properties.getRefreshTtl());
    }

    public void clear(HttpServletResponse response) {
        add(response, ACCESS_COOKIE, "", ACCESS_PATH, Duration.ZERO);
        add(response, REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO);
    }

    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, value)
                .httpOnly(true).secure(secure).sameSite(sameSite).path(path).maxAge(maxAge).build().toString());
    }
}
