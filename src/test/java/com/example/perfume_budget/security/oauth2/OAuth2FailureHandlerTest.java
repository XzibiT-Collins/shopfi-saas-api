package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.support.TestLinkBuilders;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.tenancy.TenantLinkBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AUTH-01 AC1: the OAuth2 failure redirect targets the tenant origin and never carries exception text. */
class OAuth2FailureHandlerTest {

    static final String SECRET_TEXT = "Email not found from OAuth2 provider for jane@secret.test";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private OAuth2AuthenticationFailureHandler handler(OAuth2RedirectValidator validator) {
        TenantLinkBuilder links = TestLinkBuilders.linkBuilder("shopfi.test", "http://localhost:3000/reset-password");
        OAuth2AuthenticationFailureHandler h = new OAuth2AuthenticationFailureHandler(validator, links);
        ReflectionTestUtils.setField(h, "fallbackRedirectUri", "https://fallback.example.com/oauth2/redirect");
        return h;
    }

    private String locationFor(OAuth2AuthenticationFailureHandler h, String stashedRedirect) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (stashedRedirect != null) {
            req.setAttribute(HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE, stashedRedirect);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        h.onAuthenticationFailure(req, res, new OAuth2AuthenticationException(SECRET_TEXT));
        assertThat(res.getStatus()).isEqualTo(302);
        return res.getRedirectedUrl();
    }

    @Test
    void tenantKnown_validatedRedirectUriIsUsed_withFixedErrorCode() throws Exception {
        OAuth2RedirectValidator v = mock(OAuth2RedirectValidator.class);
        when(v.isAllowedForTenant("https://acme.shopfi.test/oauth2/return", "acme")).thenReturn(true);
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));

        String loc = locationFor(handler(v), "https://acme.shopfi.test/oauth2/return");

        assertThat(loc).isEqualTo("https://acme.shopfi.test/oauth2/return?error=oauth2_failed");
        assertThat(loc).doesNotContain("secret").doesNotContain("jane");
    }

    @Test
    void tenantKnown_untrustedRedirectUriIsDiscarded_andTheTenantLoginPageIsUsed() throws Exception {
        OAuth2RedirectValidator v = mock(OAuth2RedirectValidator.class);
        when(v.isAllowedForTenant(anyString(), anyString())).thenReturn(false);
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));

        String loc = locationFor(handler(v), "https://evil.example.net/steal");

        assertThat(loc).isEqualTo("https://acme.shopfi.test/login?error=oauth2_failed");
    }

    @Test
    void tenantKnown_noStashedRedirect_usesTheTenantOrigin_notTheGlobalFallback() throws Exception {
        TenantContext.set(TenantIdentity.tenant("shop", "tenant_shop"));
        String loc = locationFor(handler(mock(OAuth2RedirectValidator.class)), null);
        assertThat(loc).isEqualTo("https://shop.example.com/login?error=oauth2_failed");
    }

    @Test
    void noTenantResolved_fallsBackToTheGlobalUri_stillWithoutExceptionText() throws Exception {
        String loc = locationFor(handler(mock(OAuth2RedirectValidator.class)), "https://evil.example.net/steal");
        assertThat(loc).isEqualTo("https://fallback.example.com/oauth2/redirect?error=oauth2_failed");
    }
}
