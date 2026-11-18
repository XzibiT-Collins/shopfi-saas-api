package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.CustomDomainStatus;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.OnboardingProperties;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.security.oauth2.OAuth2RedirectValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DOMAIN-02 AC2/AC4 (unit): the shared host rule and the VERIFIED-only reads of the custom domain by the link builder
 * and the OAuth2 redirect gate.
 */
class TenantHostLookupTest {

    TenantRegistryService registry;
    TenantResolutionProperties props;
    TenantHostLookup lookup;

    static Tenant tenant(String slug, String domain, CustomDomainStatus status) {
        return Tenant.builder().slug(slug).schemaName("tenant_" + slug).status(TenantStatus.ACTIVE)
                .customDomain(domain).customDomainStatus(status).customDomainToken(domain == null ? null : "tok").build();
    }

    @BeforeEach
    void setUp() {
        registry = mock(TenantRegistryService.class);
        when(registry.findBySlug(anyString())).thenReturn(Optional.empty());
        when(registry.findByVerifiedCustomDomain(anyString())).thenReturn(Optional.empty());
        props = new TenantResolutionProperties();
        props.setBaseDomain("shopfi.test");
        props.setPlatformDomains(List.of("shopfi.example", "api.shopfi.example"));
        lookup = new TenantHostLookup(registry, props);
    }

    @Test
    void customDomainHostGoesToTheVerifiedLookupOnly_lowercased() {
        Tenant acme = tenant("acme", "shop.acme-brand.com", CustomDomainStatus.VERIFIED);
        when(registry.findByVerifiedCustomDomain("shop.acme-brand.com")).thenReturn(Optional.of(acme));
        assertThat(lookup.findByHost("Shop.ACME-brand.com")).contains(acme);
        verify(registry, never()).findBySlug(anyString());
    }

    @Test
    void hostUnderTheBaseDomainIsResolvedBySlugOnly_neverByCustomDomain() {
        Tenant acme = tenant("acme", null, null);
        when(registry.findBySlug("acme")).thenReturn(Optional.of(acme));
        assertThat(lookup.findByHost("acme.shopfi.test")).contains(acme);
        assertThat(lookup.findByHost("a.b.shopfi.test")).isEmpty();
        assertThat(lookup.findByHost("www.shopfi.test")).isEmpty();
        assertThat(lookup.findByHost("shopfi.test")).isEmpty();
        verify(registry, never()).findByVerifiedCustomDomain(anyString());
    }

    @Test
    void platformDomainsAndBlankHostsResolveNothing() {
        assertThat(lookup.findByHost("api.shopfi.example")).isEmpty();
        assertThat(lookup.findByHost(null)).isEmpty();
        assertThat(lookup.findByHost(" ")).isEmpty();
        verify(registry, never()).findByVerifiedCustomDomain(anyString());
        verify(registry, never()).findBySlug(anyString());
    }

    @Test
    void tenantVerifiedCustomDomain_isEmptyUnlessVerified() {
        assertThat(tenant("a", "shop.acme-brand.com", CustomDomainStatus.VERIFIED).verifiedCustomDomain()).contains("shop.acme-brand.com");
        assertThat(tenant("a", "shop.acme-brand.com", CustomDomainStatus.PENDING).verifiedCustomDomain()).isEmpty();
        assertThat(tenant("a", "shop.acme-brand.com", CustomDomainStatus.FAILED).verifiedCustomDomain()).isEmpty();
        assertThat(tenant("a", null, null).verifiedCustomDomain()).isEmpty();
    }

    @Test
    void linkBuilderUsesTheCustomDomainOnlyWhenVerified_elseSlugBaseDomain() {
        TenantLinkBuilder links = new TenantLinkBuilder(registry, props, new OnboardingProperties(), "", "/reset-password", "/login");
        when(registry.findBySlug("acme")).thenReturn(Optional.of(tenant("acme", "shop.acme-brand.com", CustomDomainStatus.VERIFIED)));
        when(registry.findBySlug("bravo")).thenReturn(Optional.of(tenant("bravo", "shop.bravo-goods.net", CustomDomainStatus.PENDING)));
        when(registry.findBySlug("carol")).thenReturn(Optional.of(tenant("carol", "shop.carol-store.org", CustomDomainStatus.FAILED)));
        assertThat(links.resetPasswordLink("acme", "T")).isEqualTo("https://shop.acme-brand.com/reset-password?resetPasswordToken=T");
        assertThat(links.resetPasswordLink("bravo", "T")).isEqualTo("https://bravo.shopfi.test/reset-password?resetPasswordToken=T");
        assertThat(links.resetPasswordLink("carol", "T")).isEqualTo("https://carol.shopfi.test/reset-password?resetPasswordToken=T");
        assertThat(links.oauth2FailureRedirect("acme")).contains("https://shop.acme-brand.com/login");
        assertThat(links.oauth2FailureRedirect("bravo")).contains("https://bravo.shopfi.test/login");
    }

    @Test
    void oauth2RedirectGateHonoursOnlyVerifiedCustomDomains() {
        props.getHeaderResolution().setEnabled(false);
        OAuth2RedirectValidator v = new OAuth2RedirectValidator(registry, props, lookup);
        Tenant acme = tenant("acme", "shop.acme-brand.com", CustomDomainStatus.VERIFIED);
        Tenant bravo = tenant("bravo", "shop.bravo-goods.net", CustomDomainStatus.PENDING);
        when(registry.findBySlug("acme")).thenReturn(Optional.of(acme));
        when(registry.findBySlug("bravo")).thenReturn(Optional.of(bravo));
        when(registry.findByVerifiedCustomDomain("shop.acme-brand.com")).thenReturn(Optional.of(acme));

        assertThat(v.resolveAllowedTenant("https://shop.acme-brand.com/oauth2/redirect", null)).contains("acme");
        assertThat(v.resolveAllowedTenant("https://shop.bravo-goods.net/oauth2/redirect", null)).isEmpty();
        assertThat(v.isAllowedForTenant("https://shop.acme-brand.com/oauth2/redirect", "acme")).isTrue();
        assertThat(v.isAllowedForTenant("https://shop.bravo-goods.net/oauth2/redirect", "bravo")).isFalse();
        assertThat(v.isAllowedForTenant("https://bravo.shopfi.test/oauth2/redirect", "bravo")).isTrue();
    }
}
