package com.example.perfume_budget.controller;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.entitlement.RequiresFeature;
import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.front_desk.request.CreateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskPermissionTemplateUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskUserPermissionOverrideUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.UpdateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskPermissionTemplateResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserPermissionsResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserSummaryResponse;
import com.example.perfume_budget.service.interfaces.AdminFrontDeskManagementService;
import com.example.perfume_budget.utils.ConcurrencyTag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/front-desk")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminFrontDeskManagementController {

    private final AdminFrontDeskManagementService adminFrontDeskManagementService;

    @GetMapping("/template")
    public ResponseEntity<CustomApiResponse<FrontDeskPermissionTemplateResponse>> getDefaultTemplate() {
        FrontDeskPermissionTemplateResponse response = adminFrontDeskManagementService.getDefaultTemplate();
        return templateEtag(response);
    }

    @PutMapping("/template")
    public ResponseEntity<CustomApiResponse<FrontDeskPermissionTemplateResponse>> updateDefaultTemplate(
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody FrontDeskPermissionTemplateUpdateRequest request) {
        return templateEtag(adminFrontDeskManagementService.updateDefaultTemplate(request, ifMatch));
    }

    private static ResponseEntity<CustomApiResponse<FrontDeskPermissionTemplateResponse>> templateEtag(
            FrontDeskPermissionTemplateResponse response) {
        return ResponseEntity.ok().eTag(ConcurrencyTag.header(response.version())).body(CustomApiResponse.success(response));
    }

    private static ResponseEntity<CustomApiResponse<FrontDeskUserPermissionsResponse>> permissionsEtag(
            FrontDeskUserPermissionsResponse response) {
        return ResponseEntity.ok().eTag(ConcurrencyTag.header(response.version())).body(CustomApiResponse.success(response));
    }

    @GetMapping("/users")
    public ResponseEntity<CustomApiResponse<List<FrontDeskUserSummaryResponse>>> getFrontDeskUsers() {
        return ResponseEntity.ok(CustomApiResponse.success(adminFrontDeskManagementService.getFrontDeskUsers()));
    }

    @RequiresFeature(value = PlanFeature.MAX_STAFF, mode = RequiresFeature.Mode.LIMIT)
    @PostMapping("/users")
    public ResponseEntity<CustomApiResponse<FrontDeskUserSummaryResponse>> createFrontDeskUser(
            @Valid @RequestBody CreateFrontDeskUserRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(adminFrontDeskManagementService.createFrontDeskUser(request)));
    }

    @RequiresFeature(value = PlanFeature.MAX_STAFF, mode = RequiresFeature.Mode.LIMIT)
    @PatchMapping("/users/{userId}/assign")
    public ResponseEntity<CustomApiResponse<FrontDeskUserSummaryResponse>> assignUserToFrontDesk(@PathVariable Long userId) {
        return ResponseEntity.ok(CustomApiResponse.success(adminFrontDeskManagementService.assignUserToFrontDesk(userId)));
    }

    @RequiresFeature(value = PlanFeature.MAX_STAFF, mode = RequiresFeature.Mode.LIMIT)
    @PatchMapping("/users/{userId}")
    public ResponseEntity<CustomApiResponse<FrontDeskUserSummaryResponse>> updateFrontDeskUser(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateFrontDeskUserRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(adminFrontDeskManagementService.updateFrontDeskUser(userId, request)));
    }

    @GetMapping("/users/{userId}/permissions")
    public ResponseEntity<CustomApiResponse<FrontDeskUserPermissionsResponse>> getUserPermissions(@PathVariable Long userId) {
        return permissionsEtag(adminFrontDeskManagementService.getUserPermissions(userId));
    }

    @PutMapping("/users/{userId}/permissions")
    public ResponseEntity<CustomApiResponse<FrontDeskUserPermissionsResponse>> updateUserPermissions(
            @PathVariable Long userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody FrontDeskUserPermissionOverrideUpdateRequest request) {
        return permissionsEtag(adminFrontDeskManagementService.updateUserPermissions(userId, request, ifMatch));
    }
}
