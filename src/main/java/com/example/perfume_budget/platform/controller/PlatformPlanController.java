package com.example.perfume_budget.platform.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.plan.dto.PlanAuditPageView;
import com.example.perfume_budget.platform.plan.dto.PlanCatalogView;
import com.example.perfume_budget.platform.plan.dto.PlanCreateRequest;
import com.example.perfume_budget.platform.plan.dto.PlanMatrixRequest;
import com.example.perfume_budget.platform.plan.dto.PlanUpdateRequest;
import com.example.perfume_budget.platform.plan.dto.PlanView;
import com.example.perfume_budget.platform.plan.service.PlanAdminService;
import com.example.perfume_budget.platform.plan.service.PlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform plan catalog (PLAN-01), contract docs/api/plans.md sections 4-10. SUPER_ADMIN only, twice: the class
 * {@code @PreAuthorize} and the SecurityConfig matcher {@code /api/v1/platform/** -> SUPER_ADMIN}; tenant tokens of any role
 * never authenticate here (401). Writes accept {@code application/json} only (415 otherwise) and stay behind the platform
 * CORS / Origin guard. There is no delete (deactivate with {@code active: false}) and no per-plan GET.
 */
@RestController
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/api/v1/platform/plans")
@RequiredArgsConstructor
public class PlatformPlanController {

    private final PlanService planService;
    private final PlanAdminService adminService;

    @GetMapping
    public ResponseEntity<CustomApiResponse<PlanCatalogView>> list() {
        return ResponseEntity.ok(CustomApiResponse.success(planService.catalog()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomApiResponse<PlanView>> create(@Valid @RequestBody PlanCreateRequest request,
                                                              @AuthenticationPrincipal PlatformPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomApiResponse.success(adminService.create(request, actor)));
    }

    /** Declared before {@code /{id}} for readability; Spring prefers the literal path anyway. */
    @PutMapping(path = "/matrix", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomApiResponse<PlanCatalogView>> matrix(@Valid @RequestBody PlanMatrixRequest request,
                                                                     @AuthenticationPrincipal PlatformPrincipal actor) {
        return ResponseEntity.ok(CustomApiResponse.success(adminService.matrix(request, actor)));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomApiResponse<PlanView>> update(@PathVariable long id,
                                                              @Valid @RequestBody PlanUpdateRequest request,
                                                              @AuthenticationPrincipal PlatformPrincipal actor) {
        return ResponseEntity.ok(CustomApiResponse.success(adminService.update(id, request, actor)));
    }

    @GetMapping("/audit")
    public ResponseEntity<CustomApiResponse<PlanAuditPageView>> audit(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long planId,
            @RequestParam(required = false) String tenantSlug) {
        return ResponseEntity.ok(CustomApiResponse.success(adminService.audit(page, size, planId, tenantSlug)));
    }
}
