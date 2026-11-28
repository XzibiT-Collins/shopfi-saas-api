package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.billing.credentials.BillingCredentialService;
import com.example.perfume_budget.platform.billing.settings.BillingViews.CredentialListView;
import com.example.perfume_budget.platform.billing.settings.BillingViews.CredentialStatusView;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-environment Paystack credentials of the platform (BILL-01, billing.md 4.4, S2). SUPER_ADMIN only (class
 * {@code @PreAuthorize} plus the platform matcher). WRITE-ONLY: no response, log line or audit row carries a key; the views
 * hold {@code configured}, the last 4 characters and metadata. The environment in the path is exactly {@code test} or
 * {@code live} (anything else is 404). Writes and verification are throttled per admin.
 */
@RestController
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/api/v1/platform/billing/credentials")
@RequiredArgsConstructor
public class PlatformBillingCredentialController {

    private final BillingCredentialService credentials;
    private final BillingAdminThrottle throttle;

    @GetMapping
    public ResponseEntity<CustomApiResponse<CredentialListView>> list() {
        return ResponseEntity.ok(CustomApiResponse.success(new CredentialListView(credentials.list())));
    }

    @PutMapping(path = "/{environment}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomApiResponse<CredentialStatusView>> put(@PathVariable String environment,
                                                                       @RequestBody JsonNode request,
                                                                       @AuthenticationPrincipal PlatformPrincipal actor) {
        BillingEnvironment env = environment(environment);
        PlatformBillingSettingsController.requireObject(request);
        throttle.write(actor);
        return ResponseEntity.ok(CustomApiResponse.success(credentials.put(env, request, actor)));
    }

    @PostMapping("/{environment}/verify")
    public ResponseEntity<CustomApiResponse<CredentialStatusView>> verify(@PathVariable String environment,
                                                                          @AuthenticationPrincipal PlatformPrincipal actor) {
        BillingEnvironment env = environment(environment);
        throttle.verifyCredentials(actor);
        return ResponseEntity.ok(CustomApiResponse.success(credentials.verify(env, actor)));
    }

    private static BillingEnvironment environment(String path) {
        return BillingEnvironment.fromPath(path).orElseThrow(BillingErrors::unknownEnvironment);
    }
}
