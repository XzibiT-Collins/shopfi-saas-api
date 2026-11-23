package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.domain.CustomDomainRejectedException;
import com.example.perfume_budget.platform.domain.CustomDomains;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.OnboardingProperties;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.security.cors.CorsProperties;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.service.CheckoutCallbackUrlProvider;
import com.example.perfume_budget.support.TestLinkBuilders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * DOMAIN-FD: the production domain is configuration ({@code tenant.base-domain}, env TENANT_BASE_DOMAIN, plus the CNAME
 * target and platform hosts), never code. Every consumer of the property is run with TWO different values and must
 * follow the value: link builders, onboarding URLs (see TenantOnboardingServiceTest), the Paystack callback, the CORS
 * decision and the custom-domain reservation rules.
 */
class DomainConfigurationTest {

    static final String CURRENT = "shop.uniitflow.com";   // the running deployment's base domain (a plain value here)
    static final String OTHER = "stores.brand-example.io";

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static TenantLinkBuilder builder(String base) {
        return TestLinkBuilders.linkBuilder(base, "");
    }

    @Test
    void everyLink_followsTheConfiguredBaseDomain() {
        for (String base : List.of(CURRENT, OTHER)) {
            TenantLinkBuilder b = builder(base);
            assertThat(b.origin("acme")).contains("https://acme." + base);
            assertThat(b.resetPasswordLink("acme", "T"))
                    .isEqualTo("https://acme." + base + "/reset-password?resetPasswordToken=T");
            assertThat(b.adminLoginLink("acme")).contains("https://acme." + base + "/admin/login");
            assertThat(b.oauth2FailureRedirect("acme")).contains("https://acme." + base + "/login");
        }
        assertThat(builder(CURRENT).origin("acme")).isNotEqualTo(builder(OTHER).origin("acme"));
    }

    @Test
    void baseDomainIsTrimmedAndLowerCased_andBlankMeansNoDerivedOrigin() {
        assertThat(builder("  Shop.UniitFlow.COM ").origin("acme")).contains("https://acme.shop.uniitflow.com");
        assertThat(builder("").origin("acme")).isEmpty();
        assertThat(builder("   ").origin("acme")).isEmpty();
        assertThat(TenantLinkBuilder.subdomainOrigin("https", "acme", null)).isEmpty();
        assertThat(TenantLinkBuilder.subdomainOrigin("https", " ", CURRENT)).isEmpty();
    }

    @Test
    void verifiedCustomDomainStillWinsOverTheBaseDomain() {
        for (String base : List.of(CURRENT, OTHER)) {
            assertThat(builder(base).origin("shop")).contains("https://shop.example.com");
        }
    }

    @Test
    void schemeComesFromOnboardingPublicScheme() {
        TenantRegistryService registry = Mockito.mock(TenantRegistryService.class);
        when(registry.findBySlug("acme")).thenReturn(Optional.of(Tenant.builder().slug("acme").build()));
        TenantResolutionProperties props = new TenantResolutionProperties();
        props.setBaseDomain(OTHER);
        OnboardingProperties onboarding = new OnboardingProperties();
        onboarding.setPublicScheme("http");
        TenantLinkBuilder b = new TenantLinkBuilder(registry, props, onboarding, "", "/reset-password", "/login");
        assertThat(b.origin("acme")).contains("http://acme." + OTHER);
    }

    @Test
    void checkoutCallbackUrl_followsTheBaseDomain() {
        for (String base : List.of(CURRENT, OTHER)) {
            TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
            assertThat(new CheckoutCallbackUrlProvider(builder(base), "/checkout/callback").callbackUrl())
                    .contains("https://acme." + base + "/checkout/callback");
        }
    }

    // ---- CORS ------------------------------------------------------------------------------------------------------

    private static TenantOriginPolicy corsPolicy(String base) {
        TenantRegistryService registry = Mockito.mock(TenantRegistryService.class);
        when(registry.findBySlug(anyString())).thenReturn(Optional.empty());
        when(registry.findByVerifiedCustomDomain(anyString())).thenReturn(Optional.empty());
        when(registry.findBySlug("acme")).thenReturn(Optional.of(
                Tenant.builder().slug("acme").schemaName("tenant_acme").status(TenantStatus.ACTIVE).build()));
        TenantResolutionProperties props = new TenantResolutionProperties();
        props.setBaseDomain(base);
        return new TenantOriginPolicy(registry, props, new CorsProperties(),
                new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active", "prod"));
    }

    @Test
    void corsAllowsExactlyTheOriginsUnderTheConfiguredBaseDomain() {
        TenantOriginPolicy current = corsPolicy(CURRENT);
        TenantOriginPolicy other = corsPolicy(OTHER);
        assertThat(current.isAllowed("https://acme." + CURRENT)).isTrue();
        assertThat(current.isAllowed("https://acme." + OTHER)).isFalse();
        assertThat(other.isAllowed("https://acme." + OTHER)).isTrue();
        assertThat(other.isAllowed("https://acme." + CURRENT)).isFalse();
        // the base domain itself and a two-label slug are never a tenant origin
        assertThat(current.isAllowed("https://" + CURRENT)).isFalse();
        assertThat(current.isAllowed("https://x.acme." + CURRENT)).isFalse();
    }

    @Test
    void corsWithBlankBaseDomainAllowsNoSubdomainOrigin() {
        assertThat(corsPolicy("").isAllowed("https://acme." + CURRENT)).isFalse();
    }

    // ---- custom domains: platform names follow the properties --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {CURRENT, OTHER})
    void customDomainPolicyReservesTheConfiguredBaseDomainAndItsParents(String base) {
        CustomDomains.Policy p = CustomDomains.Policy.of(base, List.of("api." + base), List.of(), List.of());
        for (String reserved : List.of(base, "x." + base, "api." + base)) {
            assertThatThrownBy(() -> CustomDomains.normalize(reserved, p))
                    .isInstanceOfSatisfying(CustomDomainRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(CustomDomains.PLATFORM));
        }
        assertThat(CustomDomains.normalize("shop.acme-brand.com", p)).isEqualTo("shop.acme-brand.com");
    }

    @Test
    void aNameReservedUnderOneBaseDomainIsFreeUnderAnother() {
        CustomDomains.Policy current = CustomDomains.Policy.of(CURRENT, List.of(), List.of(), List.of());
        CustomDomains.Policy other = CustomDomains.Policy.of(OTHER, List.of(), List.of(), List.of());
        assertThat(CustomDomains.normalize("x." + OTHER, current)).isEqualTo("x." + OTHER);
        assertThat(CustomDomains.normalize("x." + CURRENT, other)).isEqualTo("x." + CURRENT);
    }
}
