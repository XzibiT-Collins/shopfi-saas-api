package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.support.TestLinkBuilders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantLinkBuilderTest {

    @Test
    void links_differPerTenant() {
        TenantLinkBuilder b = TestLinkBuilders.linkBuilder("shopfi.test", "");
        assertThat(b.resetPasswordLink("acme", "T")).isEqualTo("https://acme.shopfi.test/reset-password?resetPasswordToken=T");
        assertThat(b.resetPasswordLink("shop", "T")).isEqualTo("https://shop.example.com/reset-password?resetPasswordToken=T");
    }

    @Test
    void unknownTenantOrPlatformOrBlankSlug_neverYieldsAHostDerivedFromInput() {
        TenantLinkBuilder b = TestLinkBuilders.linkBuilder("shopfi.test", "");
        assertThat(b.origin("evil")).isEmpty(); // not in the registry: no link host is invented from the slug
        assertThat(b.origin(TenantContext.PLATFORM_SLUG)).isEmpty();
        assertThat(b.origin(null)).isEmpty();
        assertThatThrownBy(() -> b.resetPasswordLink(null, "T")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> b.resetPasswordLink("evil", "T")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fallbackTemplateSupportsASlugPlaceholder() {
        TenantLinkBuilder b = TestLinkBuilders.linkBuilder("", "http://{slug}.localhost:3000/reset-password");
        assertThat(b.resetPasswordLink("acme", "T"))
                .isEqualTo("http://acme.localhost:3000/reset-password?tenant=acme&resetPasswordToken=T");
    }
}
