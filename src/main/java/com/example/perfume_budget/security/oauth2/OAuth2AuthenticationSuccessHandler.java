package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.model.User;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.utils.AuthCookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * On successful OAuth2 login (on the single API host), set the auth cookies and redirect the
 * browser back to the tenant frontend's validated {@code redirect_uri} (stashed on the request
 * by {@link HttpCookieOAuth2AuthorizationRequestRepository}). The tenant context was restored
 * before the user was loaded, so the issued JWT carries the correct tenant claim.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthCookieUtil authCookieUtil;
    private final OAuth2RedirectValidator redirectValidator;

    @Value("${application.oauth2.authorized-redirect-uris}")
    private String fallbackRedirectUri;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (response.isCommitted()) {
            log.debug("Response already committed; cannot redirect after OAuth2 success");
            return;
        }

        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
        User user = oAuth2User.getUser();

        authCookieUtil.setAuthCookies(request, response, user);

        String redirectUri = (String) request.getAttribute(
                HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE);

        // Open-redirect gate (defense in depth): only follow the redirect if its host still
        // belongs to the authenticated tenant; otherwise use the safe configured fallback.
        String safeRedirect = fallbackRedirectUri;
        if (redirectUri != null && redirectValidator.isAllowedForTenant(redirectUri, TenantContext.getSlug())) {
            safeRedirect = redirectUri;
        } else if (redirectUri != null) {
            log.warn("Discarding untrusted OAuth2 redirect_uri for tenant {}: {}",
                    TenantContext.getSlug(), redirectUri);
        }

        String target = UriComponentsBuilder.fromUriString(safeRedirect)
                .queryParam("success", "true")
                .build().toUriString();

        log.info("OAuth2 success for {}; redirecting to {}", user.getEmail(), target);
        getRedirectStrategy().sendRedirect(request, response, target);
    }
}
