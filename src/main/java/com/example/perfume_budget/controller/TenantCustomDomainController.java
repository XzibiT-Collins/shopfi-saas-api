package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.exception.UnauthorizedException;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.platform.domain.CustomDomainRequest;
import com.example.perfume_budget.platform.domain.CustomDomainService;
import com.example.perfume_budget.platform.domain.CustomDomainView;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.utils.AuthUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DOMAIN-02: the tenant admin manages its OWN shop's custom domain (the tenant is the request's resolved tenant, never
 * a parameter). Contract: docs/api/custom-domains.md.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/custom-domain")
@RequiredArgsConstructor
public class TenantCustomDomainController {

    private final CustomDomainService service;
    private final AuthUserUtil authUserUtil;

    @GetMapping
    public ResponseEntity<CustomApiResponse<CustomDomainView>> get() {
        return ResponseEntity.ok(CustomApiResponse.success(service.get(TenantContext.getSlug())));
    }

    /** Claims (or replaces) the custom domain; it starts PENDING and is not honoured until verified. */
    @PutMapping
    public ResponseEntity<CustomApiResponse<CustomDomainView>> request(@RequestBody CustomDomainRequest body) {
        return ResponseEntity.ok(CustomApiResponse.success(
                service.request(TenantContext.getSlug(), body == null ? null : body.domain(), actor())));
    }

    /** Checks the DNS TXT proof now (rate limited); VERIFIED or FAILED. */
    @PostMapping("/verify")
    public ResponseEntity<CustomApiResponse<CustomDomainView>> verify() {
        return ResponseEntity.ok(CustomApiResponse.success(service.verify(TenantContext.getSlug(), actor())));
    }

    @DeleteMapping
    public ResponseEntity<CustomApiResponse<CustomDomainView>> remove() {
        return ResponseEntity.ok(CustomApiResponse.success(service.remove(TenantContext.getSlug(), actor())));
    }

    private CustomDomainService.Actor actor() {
        User user = authUserUtil.getCurrentUser();
        if (user == null) {
            throw new UnauthorizedException("Full authentication is required to access this resource");
        }
        return new CustomDomainService.Actor(CustomDomainService.ActorType.TENANT_ADMIN, user.getId(), user.getEmail());
    }
}
