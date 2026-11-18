package com.example.perfume_budget.platform.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.domain.CustomDomainService;
import com.example.perfume_budget.platform.domain.CustomDomainView;
import com.example.perfume_budget.platform.domain.PlatformCustomDomainView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DOMAIN-02: the platform super-admin can inspect (with the audit history), re-verify and remove any tenant's custom
 * domain. Claiming a domain is the tenant admin's action only. Contract: docs/api/custom-domains.md.
 */
@RestController
@org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/api/v1/platform/tenants/{slug}/custom-domain")
@RequiredArgsConstructor
public class PlatformTenantDomainController {

    private final CustomDomainService service;

    @GetMapping
    public ResponseEntity<CustomApiResponse<PlatformCustomDomainView>> getTenantCustomDomain(@PathVariable String slug) {
        return ResponseEntity.ok(CustomApiResponse.success(service.platformView(slug)));
    }

    @PostMapping("/verify")
    public ResponseEntity<CustomApiResponse<CustomDomainView>> verifyTenantCustomDomain(
            @PathVariable String slug, @AuthenticationPrincipal PlatformPrincipal actor) {
        return ResponseEntity.ok(CustomApiResponse.success(service.verify(slug, actor(actor))));
    }

    @DeleteMapping
    public ResponseEntity<CustomApiResponse<CustomDomainView>> removeTenantCustomDomain(
            @PathVariable String slug, @AuthenticationPrincipal PlatformPrincipal actor) {
        return ResponseEntity.ok(CustomApiResponse.success(service.remove(slug, actor(actor))));
    }

    private static CustomDomainService.Actor actor(PlatformPrincipal p) {
        return new CustomDomainService.Actor(CustomDomainService.ActorType.PLATFORM_ADMIN, p.id(), p.email());
    }
}
