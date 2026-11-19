package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.TenantHostLookup;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AUTH-02 AC2: a redirect is trusted by scheme, user-info and port as well as by host. */
class OAuth2RedirectValidatorTest {

    private OAuth2RedirectValidator validator(boolean dev) {
        TenantResolutionProperties props = new TenantResolutionProperties();
        props.setBaseDomain("shopfi.test");
        props.getHeaderResolution().setEnabled(dev);
        TenantRegistryService registry = mock(TenantRegistryService.class);
        when(registry.findBySlug(anyString())).thenAnswer(i -> Optional.of(Tenant.builder().slug(i.getArgument(0)).build()));
        return new OAuth2RedirectValidator(registry, props, mock(TenantHostLookup.class));
    }

    @Test
    void plainHttpsTenantUrlsAreAllowed() {
        OAuth2RedirectValidator v = validator(false);
        assertThat(v.isAllowedForTenant("https://acme.shopfi.test/oauth2/redirect", "acme")).isTrue();
        assertThat(v.isAllowedForTenant("https://acme.shopfi.test:443/oauth2/redirect", "acme")).isTrue();
        assertThat(v.isAllowedForTenant("https://ACME.shopfi.test", "acme")).isTrue();
    }

    @Test
    void anotherSchemeUserInfoOrPortIsRefusedEvenWithTheRightHost() {
        OAuth2RedirectValidator v = validator(false);
        for (String bad : new String[]{
                "javascript://acme.shopfi.test/%0aalert(1)",
                "data://acme.shopfi.test/x",
                "ftp://acme.shopfi.test/x",
                "http://acme.shopfi.test/x",                  // plain http only in dev
                "https://evil.com@acme.shopfi.test/",
                "https://acme.shopfi.test:evil@acme.shopfi.test/",
                "https://acme.shopfi.test:8443/x",
                "//acme.shopfi.test/x"}) {
            assertThat(v.isAllowedForTenant(bad, "acme")).as(bad).isFalse();
        }
    }

    @Test
    void inDevPlainHttpAndPortsAreAllowedButNeverUserInfoOrOtherSchemes() {
        OAuth2RedirectValidator v = validator(true);
        assertThat(v.isAllowedForTenant("http://acme.shopfi.test:3000/oauth2/redirect", "acme")).isTrue();
        assertThat(v.isAllowedForTenant("http://localhost:3000/oauth2/redirect", "acme")).isTrue();
        assertThat(v.isAllowedForTenant("javascript://acme.shopfi.test/%0aalert(1)", "acme")).isFalse();
        assertThat(v.isAllowedForTenant("http://evil.com@localhost:3000/", "acme")).isFalse();
    }
}
