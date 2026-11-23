package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.front_desk.request.CreateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskPermissionTemplateUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskUserPermissionOverrideUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.UpdateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskPermissionTemplateResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserPermissionsResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserSummaryResponse;

import java.util.List;

public interface AdminFrontDeskManagementService {
    FrontDeskPermissionTemplateResponse getDefaultTemplate();

    FrontDeskPermissionTemplateResponse updateDefaultTemplate(FrontDeskPermissionTemplateUpdateRequest request);

    /** @param ifMatch the ETag of the last read ({@code null}: no check, last write wins); stale: 409 {@code TEMPLATE_VERSION_CONFLICT} */
    FrontDeskPermissionTemplateResponse updateDefaultTemplate(FrontDeskPermissionTemplateUpdateRequest request, String ifMatch);

    List<FrontDeskUserSummaryResponse> getFrontDeskUsers();

    FrontDeskUserSummaryResponse createFrontDeskUser(CreateFrontDeskUserRequest request);

    FrontDeskUserSummaryResponse assignUserToFrontDesk(Long userId);

    FrontDeskUserSummaryResponse updateFrontDeskUser(Long userId, UpdateFrontDeskUserRequest request);

    FrontDeskUserPermissionsResponse getUserPermissions(Long userId);

    FrontDeskUserPermissionsResponse updateUserPermissions(Long userId, FrontDeskUserPermissionOverrideUpdateRequest request);

    /** @param ifMatch the ETag of the last read ({@code null}: no check); stale: 409 {@code USER_PERMISSIONS_VERSION_CONFLICT} */
    FrontDeskUserPermissionsResponse updateUserPermissions(Long userId, FrontDeskUserPermissionOverrideUpdateRequest request,
                                                           String ifMatch);
}
