package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantLinkBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Handles failed OAuth2 authentication by sending the browser back to the TENANT's storefront with a FIXED error
 * code. The exception text is logged server side and never put in the URL (it can contain provider details, emails
 * or internal messages).
 *
 * <p>Target, first match wins: the validated {@code redirect_uri} stashed for this login (same open-redirect gate
 * as the success handler); else the tenant's own login page ({@link TenantLinkBuilder}); else, only when no tenant
 * could be identified (state cookie missing or invalid), the global {@code OAUTH2_REDIRECT_URIS} fallback.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    /** The only value ever sent as {@code error}. */
    public static final String ERROR_CODE = "oauth2_failed";

    private final OAuth2RedirectValidator redirectValidator;
    private final TenantLinkBuilder tenantLinkBuilder;

    @Value("${application.oauth2.authorized-redirect-uris}")
    private String fallbackRedirectUri;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String slug = TenantContext.getSlug();
        String base = fallbackRedirectUri;
        String stashed = (String) request.getAttribute(
                HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE);
        if (slug != null) {
            if (stashed != null && redirectValidator.isAllowedForTenant(stashed, slug)) {
                base = stashed;
            } else {
                base = tenantLinkBuilder.oauth2FailureRedirect(slug).orElse(fallbackRedirectUri);
            }
        }

        String targetUrl = UriComponentsBuilder.fromUriString(base)
                .replaceQueryParam("error", ERROR_CODE)
                .build().toUriString();

        log.error("OAuth2 authentication failed for tenant {}: {}", slug, exception.getMessage());
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
