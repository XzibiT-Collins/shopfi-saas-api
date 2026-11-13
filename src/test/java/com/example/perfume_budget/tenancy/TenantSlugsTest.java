package com.example.perfume_budget.tenancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PROV-01 AC1: slug normalisation, reserved names, length and injection attempts (no database). */
class TenantSlugsTest {

    @Test
    void normalisesCaseAndWhitespace() {
        assertThat(TenantSlugs.normalize("  AcMe-Co1 ")).isEqualTo("acme-co1");
    }

    @Test
    void mapsToSchemaName() {
        assertThat(TenantSlugs.schemaNameFor("acme")).isEqualTo("tenant_acme");
        assertThat(TenantSlugs.schemaNameFor("acme-co")).isEqualTo("tenant_acme_co");
    }

    @Test
    void acceptsBoundaryLengths() {
        assertThat(TenantSlugs.normalize("abc")).isEqualTo("abc");
        assertThat(TenantSlugs.normalize("a".repeat(40))).hasSize(40);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "master", "tenant_template", "tenant-template", "PUBLIC", " Master ", "pg_catalog",
            "pg-temp", "pg-toast", "www", "api", "admin", "platform", "template", "postgres", "information_schema",
            "tenant_acme"})
    void rejectsReservedNames(String slug) {
        assertThatThrownBy(() -> TenantSlugs.normalize(slug)).isInstanceOf(InvalidProvisioningRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "x\"; DROP SCHEMA master CASCADE;--",
            "acme; DROP SCHEMA public CASCADE",
            "acme\" CASCADE; --",
            "acme' OR '1'='1",
            "acme/**/or/**/1=1",
            "acme\nDROP",
            "acme\u0000x",
            "ac me",
            "acme.evil",
            "acme$1",
            "../etc",
            "acme_co",          // underscore not allowed in slugs
            "-acme", "acme-", "ac--me",
            "аcme",        // cyrillic a
            "acmé",        // e-acute
            "ａｃｍｅ", // fullwidth
            "ab", "", "   ",
    })
    void rejectsInjectionAndMalformed(String slug) {
        assertThatThrownBy(() -> TenantSlugs.normalize(slug)).isInstanceOf(InvalidProvisioningRequestException.class);
    }

    @Test
    void rejectsOverLength() {
        assertThatThrownBy(() -> TenantSlugs.normalize("a".repeat(41))).isInstanceOf(InvalidProvisioningRequestException.class);
    }

    @ParameterizedTest
    @NullSource
    void rejectsNull(String slug) {
        assertThatThrownBy(() -> TenantSlugs.normalize(slug)).isInstanceOf(InvalidProvisioningRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "tenant_template", "master", "pg_catalog", "tenant_x\"; drop", "tenant_ab", "Tenant_acme",
            "tenant_acme-co", "tenant_ACME"})
    void ddlGuardRejectsNonTenantSchemas(String schema) {
        assertThatThrownBy(() -> TenantSchemaMigrator.requireTenantSchema(schema)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ddlGuardAcceptsGeneratedNames() {
        assertThat(TenantSchemaMigrator.requireTenantSchema(TenantSlugs.schemaNameFor("acme-co"))).isEqualTo("tenant_acme_co");
    }
}
