package com.example.perfume_budget.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps the default resolver to identify the tenant from the login's {@code redirect_uri} and
 * validate it via {@link OAuth2RedirectValidator} (the open-redirect gate). The tenant slug and
 * validated redirect are stored as authorization-request attributes, carried through Google via
 * {@code state}, and restored on the callback (single API host) by
 * {@link HttpCookieOAuth2AuthorizationRequestRepository}.
 */
@Slf4j
@Component
public class TenantAwareAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String AUTHORIZATION_BASE_URI = "/oauth2/authorize";
    private static final String REDIRECT_URI_PARAM = "redirect_uri";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final OAuth2RedirectValidator redirectValidator;

    public TenantAwareAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository,
                                                   OAuth2RedirectValidator redirectValidator) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, AUTHORIZATION_BASE_URI);
        this.redirectValidator = redirectValidator;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withTenant(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withTenant(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest withTenant(OAuth2AuthorizationRequest authorizationRequest,
                                                  HttpServletRequest request) {
        if (authorizationRequest == null) {
            return null;
        }
        String redirectUri = request.getParameter(REDIRECT_URI_PARAM);
        Optional<String> slug = redirectValidator.resolveAllowedTenant(redirectUri, request);
        if (slug.isEmpty()) {
            // Redirect host does not belong to any tenant: leave attributes unset so the callback
            // guard (CustomOAuth2UserService) rejects the login rather than pick a wrong schema
            // or follow an untrusted redirect.
            log.warn("OAuth2 authorize rejected: redirect_uri host not allowed for any tenant: {}", redirectUri);
            return authorizationRequest;
        }
        Map<String, Object> attributes = new HashMap<>(authorizationRequest.getAttributes());
        attributes.put(HttpCookieOAuth2AuthorizationRequestRepository.TENANT_ATTRIBUTE, slug.get());
        attributes.put(HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_ATTRIBUTE, redirectUri);
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .attributes(attributes)
                .build();
    }
}
