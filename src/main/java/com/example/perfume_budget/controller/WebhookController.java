package com.example.perfume_budget.controller;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.service.interfaces.WebhookService;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Per-tenant Paystack webhook. Each tenant registers its own URL
 * {@code /api/v1/webhook/{token}} in its Paystack dashboard. The token (opaque, stored in
 * master) selects the tenant so we can bind its schema and verify the signature with that
 * tenant's own secret. This path is bypassed by TenantResolutionFilter (no Origin), so the
 * controller sets/clears the context itself.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;
    private final TenantRegistryService tenantRegistryService;

    @PostMapping("/{token}")
    public ResponseEntity<String> processWebhook(
            @PathVariable("token") String token,
            @RequestBody String rawBody,
            @RequestHeader("X-Paystack-Signature") String signature) {

        Optional<Tenant> tenant = tenantRegistryService.findByWebhookToken(token);
        if (tenant.isEmpty() || tenant.get().getStatus() != TenantStatus.ACTIVE) {
            log.warn("Webhook received for unknown/inactive token");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Unknown webhook");
        }

        Tenant t = tenant.get();
        try {
            TenantContext.set(TenantIdentity.tenant(t.getSlug(), t.getSchemaName()));
            // Signature verification + async dispatch happen here; the async task captures the
            // tenant context via the TaskDecorator before this finally clears it.
            return webhookService.checkWebhookSignature(rawBody, signature);
        } finally {
            TenantContext.clear();
        }
    }
}
