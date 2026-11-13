package com.example.perfume_budget.controller;

import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.tenancy.*;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Dev-only trigger for {@link TenantProvisioningService} (the real saga). Enabled only when
 * {@code tenant.dev-endpoints.enabled=true} (dev profile). Remove once Stage 2 onboarding lands.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dev/tenants")
@ConditionalOnProperty(name = "tenant.dev-endpoints.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevTenantController {

    private final TenantProvisioningService provisioningService;
    private final PasswordEncoder passwordEncoder;

    @PostMapping
    public ResponseEntity<?> createTenant(@RequestBody DevCreateTenantRequest request) {
        if (request.ownerPassword() == null || request.ownerPassword().isBlank()) {
            return ResponseEntity.badRequest().body("ownerPassword is required");
        }
        try {
            ProvisioningResult result = provisioningService.provision(new ProvisioningRequest(
                    request.slug(), request.displayName(), request.ownerEmail(), request.ownerFullName(),
                    passwordEncoder.encode(request.ownerPassword().strip()), TenantPlan.FREE));
            log.info("[DEV] Provisioned tenant {} -> schema {}", result.slug(), result.schemaName());
            return ResponseEntity.status(HttpStatus.CREATED).body(new DevTenantResponse(
                    result.tenantId(), result.slug(), result.schemaName(), result.status().name()));
        } catch (InvalidProvisioningRequestException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (TenantAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (TenantProvisioningException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    public record DevCreateTenantRequest(String slug, String displayName, String ownerEmail,
                                         String ownerPassword, String ownerFullName) {
    }

    public record DevTenantResponse(Long id, String slug, String schemaName, String status) {
    }
}
