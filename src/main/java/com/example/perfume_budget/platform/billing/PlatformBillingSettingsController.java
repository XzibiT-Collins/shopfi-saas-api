package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsService;
import com.example.perfume_budget.platform.billing.settings.BillingViews.BillingSettingsView;
import com.example.perfume_budget.platform.billing.settings.BillingViews.SettingsAuditPageView;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Platform billing settings (BILL-01, billing.md section 4.1-4.3 and 4.6). SUPER_ADMIN only, twice: the class
 * {@code @PreAuthorize} and the SecurityConfig matcher {@code /api/v1/platform/** -> SUPER_ADMIN} (tenant tokens of any role
 * are 401, a non-SUPER_ADMIN platform admin 403). Writes accept {@code application/json} only (415 otherwise), are throttled
 * per admin and stay behind the platform CORS / Origin guard. Billing endpoints are never plan-gated and never 402.
 */
@RestController
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/api/v1/platform/billing/settings")
@RequiredArgsConstructor
public class PlatformBillingSettingsController {

    private final BillingSettingsService settings;
    private final BillingAdminThrottle throttle;

    @GetMapping
    public ResponseEntity<CustomApiResponse<BillingSettingsView>> get() {
        return ResponseEntity.ok(CustomApiResponse.success(settings.view()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomApiResponse<BillingSettingsView>> put(@RequestBody JsonNode request,
                                                                      @AuthenticationPrincipal PlatformPrincipal actor) {
        requireObject(request);
        throttle.write(actor);
        return ResponseEntity.ok(CustomApiResponse.success(settings.put(request, actor)));
    }

    /** No state change and no counter is consumed, so it is not a write for the throttle. */
    @PostMapping(path = "/number-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomApiResponse<Map<String, String>>> numberPreview(@RequestBody JsonNode request) {
        requireObject(request);
        return ResponseEntity.ok(CustomApiResponse.success(settings.numberPreview(request)));
    }

    @GetMapping("/audit")
    public ResponseEntity<CustomApiResponse<SettingsAuditPageView>> audit(@RequestParam(required = false) Integer page,
                                                                          @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(CustomApiResponse.success(settings.audit(page, size)));
    }

    static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BadRequestException("Request body is missing or malformed.");
        }
    }
}
