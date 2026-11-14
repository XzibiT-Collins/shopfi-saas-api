package com.example.perfume_budget.platform.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.dto.PlatformTenantPage;
import com.example.perfume_budget.platform.dto.TenantActionResponse;
import com.example.perfume_budget.platform.service.PlatformTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform tenant management (PLAT-02), contract in {@code docs/api/platform-tenants.md}. Authorised by
 * SecurityConfig ({@code /api/v1/platform/** -> SUPER_ADMIN}); the actor is the platform principal. There is
 * deliberately no PUT/PATCH/DELETE here and no endpoint that touches the audit log.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final PlatformTenantService service;

    @GetMapping
    public ResponseEntity<CustomApiResponse<PlatformTenantPage>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return ResponseEntity.ok(CustomApiResponse.success(service.list(status, page, size, sort, direction)));
    }

    @PostMapping("/{slug}/approve")
    public ResponseEntity<CustomApiResponse<TenantActionResponse>> approve(
            @PathVariable String slug, @AuthenticationPrincipal PlatformPrincipal actor) {
        return reply(service.approve(slug, actor));
    }

    @PostMapping("/{slug}/suspend")
    public ResponseEntity<CustomApiResponse<TenantActionResponse>> suspend(
            @PathVariable String slug, @AuthenticationPrincipal PlatformPrincipal actor) {
        return reply(service.suspend(slug, actor));
    }

    @PostMapping("/{slug}/reactivate")
    public ResponseEntity<CustomApiResponse<TenantActionResponse>> reactivate(
            @PathVariable String slug, @AuthenticationPrincipal PlatformPrincipal actor) {
        return reply(service.reactivate(slug, actor));
    }

    @PostMapping("/{slug}/reprovision")
    public ResponseEntity<CustomApiResponse<TenantActionResponse>> reprovision(
            @PathVariable String slug, @AuthenticationPrincipal PlatformPrincipal actor) {
        return reply(service.reprovision(slug, actor));
    }

    /** 200 when done or an idempotent no-op; 202 when the provisioning saga is already running. */
    private static ResponseEntity<CustomApiResponse<TenantActionResponse>> reply(TenantActionResponse r) {
        return ResponseEntity.status(r.inProgress() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(CustomApiResponse.success(r));
    }
}
