package com.example.perfume_budget.platform.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.dto.OnboardingStatusResponse;
import com.example.perfume_budget.platform.dto.TenantSignupRequest;
import com.example.perfume_budget.platform.dto.TenantVerifyRequest;
import com.example.perfume_budget.platform.service.OnboardingRateLimiter;
import com.example.perfume_budget.platform.service.TenantOnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public self-service onboarding. permitAll in SecurityConfig; per-IP and per-email rate limited. */
@RestController
@RequestMapping("/api/v1/platform/onboarding")
@RequiredArgsConstructor
public class TenantOnboardingController {

    private final TenantOnboardingService service;
    private final OnboardingRateLimiter limiter;

    @PostMapping("/signup")
    public ResponseEntity<CustomApiResponse<OnboardingStatusResponse>> signup(
            @Valid @RequestBody TenantSignupRequest request, HttpServletRequest http) {
        limiter.checkSignupIp(http.getRemoteAddr());
        limiter.checkSignupEmail(request.ownerEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CustomApiResponse.success("Verification code sent", service.signup(request)));
    }

    @PostMapping("/verify")
    public ResponseEntity<CustomApiResponse<OnboardingStatusResponse>> verify(
            @Valid @RequestBody TenantVerifyRequest request, HttpServletRequest http) {
        limiter.checkVerifyIp(http.getRemoteAddr());
        limiter.checkVerifyEmail(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(CustomApiResponse.success("Provisioning started", service.verify(request)));
    }

    @GetMapping("/status")
    public ResponseEntity<CustomApiResponse<OnboardingStatusResponse>> status(
            @RequestParam("slug") String slug, HttpServletRequest http) {
        limiter.checkStatusIp(http.getRemoteAddr());
        return ResponseEntity.ok(CustomApiResponse.success(service.status(slug)));
    }
}
