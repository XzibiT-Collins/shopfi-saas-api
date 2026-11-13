package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Stores the pending {@link OAuth2AuthorizationRequest} in a short-lived cookie instead of
 * the HTTP session (the app is stateless). The request's {@code tenant} attribute (added by
 * {@link TenantAwareAuthorizationRequestResolver}) rides through Google via {@code state} and
 * is used, on callback, to restore {@link TenantContext} BEFORE the user is created/looked up —
 * so OAuth2 users never land in the wrong schema.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String TENANT_ATTRIBUTE = "tenant";
    public static final String REDIRECT_URI_ATTRIBUTE = "redirect_uri";
    /** Request attribute the success handler reads to know where to send the user back. */
    public static final String REQUEST_REDIRECT_ATTRIBUTE = "OAUTH2_TENANT_REDIRECT_URI";
    private static final String COOKIE_NAME = "OAUTH2_AUTH_REQUEST";
    private static final int COOKIE_MAX_AGE_SECONDS = 180;

    private final TenantRegistryService tenantRegistryService;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return readCookie(request).map(this::deserialize).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            deleteCookie(response);
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, serialize(authorizationRequest))
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .maxAge(COOKIE_MAX_AGE_SECONDS)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        deleteCookie(response);
        if (authorizationRequest != null) {
            restoreTenantContext(authorizationRequest);
            Object redirectUri = authorizationRequest.getAttributes().get(REDIRECT_URI_ATTRIBUTE);
            if (redirectUri != null) {
                request.setAttribute(REQUEST_REDIRECT_ATTRIBUTE, redirectUri.toString());
            }
        }
        return authorizationRequest;
    }

    private void restoreTenantContext(OAuth2AuthorizationRequest authorizationRequest) {
        Object slug = authorizationRequest.getAttributes().get(TENANT_ATTRIBUTE);
        if (slug == null) {
            return;
        }
        Optional<Tenant> tenant = tenantRegistryService.findBySlug(slug.toString());
        if (tenant.isPresent() && tenant.get().getStatus() == TenantStatus.ACTIVE) {
            Tenant t = tenant.get();
            TenantContext.set(TenantIdentity.tenant(t.getSlug(), t.getSchemaName()));
        } else {
            log.warn("OAuth2 callback carried unknown/inactive tenant slug: {}", slug);
        }
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void deleteCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(authorizationRequest);
            oos.flush();
            return Base64.getUrlEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize OAuth2 authorization request", e);
        }
    }

    private OAuth2AuthorizationRequest deserialize(String value) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                return (OAuth2AuthorizationRequest) ois.readObject();
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize OAuth2 authorization request cookie: {}", e.getMessage());
            return null;
        }
    }
}
