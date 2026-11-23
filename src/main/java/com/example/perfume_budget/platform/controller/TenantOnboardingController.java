package com.example.perfume_budget.platform.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.dto.OnboardingStatusResponse;
import com.example.perfume_budget.platform.dto.StoreLookupRequest;
import com.example.perfume_budget.platform.service.StoreLookupService;
import com.example.perfume_budget.platform.dto.TenantResendRequest;
import com.example.perfume_budget.security.ClientIps;
import com.example.perfume_budget.platform.dto.TenantSignupRequest;
import com.example.perfume_budget.platform.dto.TenantVerifyRequest;
import com.example.perfume_budget.platform.service.OnboardingRateLimiter;
import com.example.perfume_budget.platform.service.TenantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public self-service onboarding. permitAll in SecurityConfig; per-IP and per-email rate limited. */
@Tag(name = "Tenant onboarding", description = "Public self-service signup. Contract: docs/api/onboarding.md. Error bodies are the CustomApiResponse envelope; 429 carries Retry-After (seconds).")
@RestController
@RequestMapping("/api/v1/platform/onboarding")
@RequiredArgsConstructor
public class TenantOnboardingController {

    private final TenantOnboardingService service;
    private final OnboardingRateLimiter limiter;
    private final StoreLookupService storeLookup;

    @Operation(summary = "Start onboarding: create a PENDING tenant and e-mail a 6-digit code")
    @ApiResponse(responseCode = "201", description = "Signup accepted, status PENDING")
    @ApiResponse(responseCode = "400", description = "Validation failure, bad or reserved slug, weak password", content = @Content)
    @ApiResponse(responseCode = "409", description = "Slug or owner e-mail already registered", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limited (Retry-After)", content = @Content)
    @PostMapping("/signup")
    public ResponseEntity<CustomApiResponse<OnboardingStatusResponse>> signup(
            @Valid @RequestBody TenantSignupRequest request, HttpServletRequest http) {
        limiter.checkSignupIp(ClientIps.rateLimitKey(http));
        limiter.checkSignupEmail(request.ownerEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CustomApiResponse.success("Verification code sent", service.signup(request)));
    }

    @Operation(summary = "Verify the e-mail code and start provisioning")
    @ApiResponse(responseCode = "202", description = "Code accepted, provisioning queued (status PROVISIONING)")
    @ApiResponse(responseCode = "400", description = "Invalid, expired, used or mismatching code (generic)", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limited or OTP lockout (Retry-After)", content = @Content)
    @PostMapping("/verify")
    public ResponseEntity<CustomApiResponse<OnboardingStatusResponse>> verify(
            @Valid @RequestBody TenantVerifyRequest request, HttpServletRequest http) {
        limiter.checkVerifyIp(ClientIps.rateLimitKey(http));
        limiter.checkVerifyEmail(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(CustomApiResponse.success("Provisioning started", service.verify(request)));
    }

    @Operation(summary = "Poll the onboarding status (PENDING, PROVISIONING, ACTIVE, FAILED)")
    @ApiResponse(responseCode = "200", description = "Current status; URLs present only when ACTIVE")
    @ApiResponse(responseCode = "400", description = "Missing slug parameter", content = @Content)
    @ApiResponse(responseCode = "404", description = "Unknown, malformed or suspended tenant", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limited (Retry-After)", content = @Content)
    @GetMapping("/status")
    public ResponseEntity<CustomApiResponse<OnboardingStatusResponse>> status(
            @RequestParam("slug") String slug, HttpServletRequest http) {
        limiter.checkStatusIp(ClientIps.rateLimitKey(http));
        return ResponseEntity.ok(CustomApiResponse.success(service.status(slug)));
    }

    /** Enumeration-safe: always 202 with the same body, whether or not a resendable signup exists. */
    @Operation(summary = "Request a fresh code (enumeration-safe: always 202 with the same body)")
    @ApiResponse(responseCode = "202", description = "Always returned when the request is well-formed and not rate limited")
    @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limited (Retry-After)", content = @Content)
    @PostMapping("/resend")
    public ResponseEntity<CustomApiResponse<Void>> resend(
            @Valid @RequestBody TenantResendRequest request, HttpServletRequest http) {
        limiter.checkResendIp(ClientIps.rateLimitKey(http));
        limiter.checkResendEmail(request.email());
        service.resend(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(CustomApiResponse.success(TenantOnboardingService.RESEND_MESSAGE, null));
    }

    /**
     * API-RD-04 "Find your store". Never says whether the store exists: always 202 with the same body for a known,
     * unknown, malformed, not-yet-active or suspended name. When an ACTIVE store matches, its owner is e-mailed the
     * sign-in link (to the owner address on record only).
     */
    @Operation(summary = "Find your store (non-enumerating: always 202 with the same body; the owner is e-mailed the sign-in link)")
    @ApiResponse(responseCode = "202", description = "Always returned when the request is well-formed and not rate limited")
    @ApiResponse(responseCode = "400", description = "Validation failure (missing or over-long slug)", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limited per IP or per store name (Retry-After)", content = @Content)
    @PostMapping("/find-store")
    public ResponseEntity<CustomApiResponse<Void>> findStore(
            @Valid @RequestBody StoreLookupRequest request, HttpServletRequest http) {
        limiter.checkFindStoreIp(ClientIps.rateLimitKey(http));
        limiter.checkFindStoreSlug(request.slug());
        storeLookup.requestLoginLink(request.slug());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(CustomApiResponse.success(StoreLookupService.MESSAGE, null));
    }
}
