package com.example.perfume_budget.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PROV-04 AC3: the dev tenant request never prints its password. */
class DevCreateTenantRequestTest {

    @Test
    void toStringRedactsThePassword() {
        var r = new DevTenantController.DevCreateTenantRequest("acme", "Acme", "o@acme.test", "S3cret-Pass!", "Owner");
        assertThat(r.toString()).doesNotContain("S3cret-Pass!").contains("ownerPassword=***").contains("slug=acme");
    }
}
