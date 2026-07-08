package com.example.perfume_budget.controller;

import com.example.perfume_budget.config.TenantSeeder;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY dev-only helper to create + migrate + seed a tenant so Stage 1 isolation can be
 * tested before real provisioning (Stage 2) exists. Enabled only when
 * {@code tenant.dev-endpoints.enabled=true} (dev profile). Remove once Stage 2 onboarding lands.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dev/tenants")
@ConditionalOnProperty(name = "tenant.dev-endpoints.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevTenantController {

    private static final String SLUG_PATTERN = "^[a-z0-9-]{3,40}$";

    private final TenantRepository tenantRepository;
    private final TenantSchemaMigrator tenantSchemaMigrator;
    private final TenantSeeder tenantSeeder;

    @PostMapping
    public ResponseEntity<?> createTenant(@RequestBody DevCreateTenantRequest request) {
        String slug = request.slug() == null ? "" : request.slug().trim().toLowerCase();
        if (!slug.matches(SLUG_PATTERN)) {
            return ResponseEntity.badRequest().body("slug must match " + SLUG_PATTERN);
        }
        if (tenantRepository.existsBySlug(slug)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("slug already exists");
        }

        String schemaName = "tenant_" + slug.replace('-', '_');

        Tenant tenant = tenantRepository.save(Tenant.builder()
                .slug(slug)
                .displayName(request.displayName() != null ? request.displayName() : slug)
                .schemaName(schemaName)
                .status(TenantStatus.PROVISIONING)
                .plan(TenantPlan.FREE)
                .ownerEmail(request.ownerEmail())
                .build());

        tenantSchemaMigrator.migrateTenant(schemaName);

        try {
            TenantContext.set(TenantIdentity.tenant(slug, schemaName));
            tenantSeeder.seedTenantDefaults(request.ownerEmail(), request.ownerPassword(), request.ownerFullName());
        } finally {
            TenantContext.clear();
        }

        tenant.setStatus(TenantStatus.ACTIVE);
        tenant = tenantRepository.save(tenant);
        log.info("[DEV] Provisioned tenant {} -> schema {}", slug, schemaName);

        return ResponseEntity.status(HttpStatus.CREATED).body(new DevTenantResponse(
                tenant.getId(), tenant.getSlug(), tenant.getSchemaName(), tenant.getStatus().name()));
    }

    public record DevCreateTenantRequest(String slug, String displayName, String ownerEmail,
                                         String ownerPassword, String ownerFullName) {
    }

    public record DevTenantResponse(Long id, String slug, String schemaName, String status) {
    }
}
