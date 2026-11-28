package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.front_desk.request.CreateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskPermissionTemplateUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskUserPermissionOverrideUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.UpdateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskPermissionTemplateResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserPermissionsResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserSummaryResponse;
import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.model.FrontDeskPermissionTemplate;
import com.example.perfume_budget.model.FrontDeskUserPermissionOverride;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.FrontDeskPermissionTemplateRepository;
import com.example.perfume_budget.repository.FrontDeskUserPermissionOverrideRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminFrontDeskManagementServiceImplTest {

    @Mock
    private FrontDeskPermissionTemplateRepository templateRepository;
    @Mock
    private FrontDeskUserPermissionOverrideRepository overrideRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private FrontDeskAccessService frontDeskAccessService;

    @Mock
    private com.example.perfume_budget.entitlement.EntitlementService entitlementService; // PLAN-02 gates (no-op mock)

    @InjectMocks
    private AdminFrontDeskManagementServiceImpl service;

    private User frontDeskUser;

    @BeforeEach
    void setUp() {
        frontDeskUser = User.builder()
                .id(5L)
                .fullName("Desk User")
                .email("desk@example.com")
                .roles(UserRole.FRONT_DESK)
                .isActive(true)
                .build();
    }

    @Test
    void getDefaultTemplate_ReturnsEnabledPermissions() {
        when(templateRepository.findAllByEnabledTrue()).thenReturn(List.of(
                FrontDeskPermissionTemplate.builder().permission(FrontDeskPermission.CUSTOMER_SEARCH).enabled(true).build()
        ));

        FrontDeskPermissionTemplateResponse response = service.getDefaultTemplate();

        assertEquals(Set.of(FrontDeskPermission.CUSTOMER_SEARCH), response.permissions());
    }

    @Test
    void updateDefaultTemplate_UpsertsAllPermissionsAndReturnsEnabledSet() {
        when(templateRepository.findAll()).thenReturn(List.of());
        when(templateRepository.findAllByEnabledTrue()).thenReturn(List.of(
                FrontDeskPermissionTemplate.builder().permission(FrontDeskPermission.WALK_IN_ORDER_CREATE).enabled(true).build()
        ));

        FrontDeskPermissionTemplateResponse response = service.updateDefaultTemplate(
                new FrontDeskPermissionTemplateUpdateRequest(Set.of(FrontDeskPermission.WALK_IN_ORDER_CREATE))
        );

        assertEquals(Set.of(FrontDeskPermission.WALK_IN_ORDER_CREATE), response.permissions());
        verify(templateRepository, times(FrontDeskPermission.values().length)).save(any(FrontDeskPermissionTemplate.class));
    }

    @Test
    void createFrontDeskUser_CreatesEncodedLocalUser() {
        when(userRepository.existsByEmail("frontdesk@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Secret123!")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FrontDeskUserSummaryResponse response = service.createFrontDeskUser(
                new CreateFrontDeskUserRequest("Front Desk", "frontdesk@example.com", "Secret123!", true)
        );

        assertEquals(UserRole.FRONT_DESK, response.role());
        assertEquals("frontdesk@example.com", response.email());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("encoded", captor.getValue().getPassword());
    }

    @Test
    void createFrontDeskUser_FailsWhenEmailExists() {
        when(userRepository.existsByEmail("frontdesk@example.com")).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> service.createFrontDeskUser(
                new CreateFrontDeskUserRequest("Front Desk", "frontdesk@example.com", "Secret123!", true)
        ));
    }

    @Test
    void assignUserToFrontDesk_UpdatesExistingUserRole() {
        User existingUser = User.builder()
                .id(1L)
                .fullName("Existing")
                .email("existing@example.com")
                .roles(UserRole.CUSTOMER)
                .build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(existingUser)).thenReturn(existingUser);

        FrontDeskUserSummaryResponse response = service.assignUserToFrontDesk(1L);

        assertEquals(UserRole.FRONT_DESK, response.role());
    }

    @Test
    void updateFrontDeskUser_RejectsNonFrontDeskTarget() {
        User customer = User.builder().id(7L).roles(UserRole.CUSTOMER).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(customer));

        assertThrows(BadRequestException.class, () -> service.updateFrontDeskUser(7L, new UpdateFrontDeskUserRequest("Name", null, null, null)));
    }

    @Test
    void updateFrontDeskUser_IgnoresBlankPassword() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(frontDeskUser));
        when(userRepository.findByEmail("desk@example.com")).thenReturn(Optional.of(frontDeskUser));
        when(userRepository.save(frontDeskUser)).thenReturn(frontDeskUser);

        FrontDeskUserSummaryResponse response = service.updateFrontDeskUser(
                5L,
                new UpdateFrontDeskUserRequest("Desk User", "desk@example.com", "   ", true)
        );

        assertEquals("desk@example.com", response.email());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void updateFrontDeskUser_RejectsShortNonBlankPassword() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(frontDeskUser));

        assertThrows(BadRequestException.class, () -> service.updateFrontDeskUser(
                5L,
                new UpdateFrontDeskUserRequest("Desk User", null, "short", null)
        ));
    }

    @Test
    void createFrontDeskUser_RejectsWeakPasswordsWithACode() {
        for (String[] c : new String[][]{{"abcdefgh", "PASSWORD_TOO_WEAK"}, {"Ab1!", "PASSWORD_TOO_SHORT"},
                {"Aa1!" + "x".repeat(70), "PASSWORD_TOO_LONG"}, {"alllower1!", "PASSWORD_TOO_WEAK"}, {"NoDigits!!", "PASSWORD_TOO_WEAK"},
                {"NoSpecial12", "PASSWORD_TOO_WEAK"}}) {
            BadRequestException e = assertThrows(BadRequestException.class, () -> service.createFrontDeskUser(
                    new CreateFrontDeskUserRequest("Front Desk", "fd@example.com", c[0], true)), c[0]);
            assertEquals(c[1], e.getCode(), c[0]);
        }
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void updateFrontDeskUser_ShortPasswordKeepsTheHistoricText() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(frontDeskUser));
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.updateFrontDeskUser(
                5L, new UpdateFrontDeskUserRequest("Desk User", null, "short", null)));
        assertEquals("Password must be at least 8 characters long", e.getMessage());
        assertEquals("PASSWORD_TOO_SHORT", e.getCode());
    }

    @Test
    void updateDefaultTemplate_StaleIfMatchIsRefusedBeforeAnythingIsWritten() {
        when(templateRepository.findAllByEnabledTrue()).thenReturn(List.of(
                FrontDeskPermissionTemplate.builder().permission(FrontDeskPermission.CUSTOMER_SEARCH).enabled(true).build()));
        String current = AdminFrontDeskManagementServiceImpl.templateVersion(Set.of(FrontDeskPermission.CUSTOMER_SEARCH));
        String stale = AdminFrontDeskManagementServiceImpl.templateVersion(Set.of(FrontDeskPermission.STOCK_TRANSFER));
        assertEquals(current, service.getDefaultTemplate().version());

        com.example.perfume_budget.exception.ApiCodeException e = assertThrows(com.example.perfume_budget.exception.ApiCodeException.class,
                () -> service.updateDefaultTemplate(new FrontDeskPermissionTemplateUpdateRequest(Set.of()), "\"" + stale + "\""));
        assertEquals("TEMPLATE_VERSION_CONFLICT", e.getCode());
        assertEquals(current, e.body().get("currentVersion"));
        verify(templateRepository, never()).save(any(FrontDeskPermissionTemplate.class));
        verify(templateRepository).lockTemplateSaves();
    }

    @Test
    void overridesVersionCoversUserAndBothSides() {
        var allow = List.of(FrontDeskUserPermissionOverride.builder().permission(FrontDeskPermission.STOCK_TRANSFER).allowed(true).build());
        var deny = List.of(FrontDeskUserPermissionOverride.builder().permission(FrontDeskPermission.STOCK_TRANSFER).allowed(false).build());
        String base = AdminFrontDeskManagementServiceImpl.overridesVersion(5L, allow);
        assertNotEquals(base, AdminFrontDeskManagementServiceImpl.overridesVersion(6L, allow));
        assertNotEquals(base, AdminFrontDeskManagementServiceImpl.overridesVersion(5L, deny));
        assertNotEquals(base, AdminFrontDeskManagementServiceImpl.overridesVersion(5L, List.of()));
        assertEquals(base, AdminFrontDeskManagementServiceImpl.overridesVersion(5L, allow));
    }

    @Test
    void getFrontDeskUsers_SummarisesEveryUserWithoutPerUserQueries() {
        User other = User.builder().id(6L).fullName("Another").email("a@example.com").roles(UserRole.FRONT_DESK).isActive(true).build();
        when(userRepository.findAllByRoles(UserRole.FRONT_DESK)).thenReturn(List.of(frontDeskUser, other));
        when(templateRepository.findAllByEnabledTrue()).thenReturn(List.of(
                FrontDeskPermissionTemplate.builder().permission(FrontDeskPermission.CUSTOMER_SEARCH).enabled(true).build(),
                FrontDeskPermissionTemplate.builder().permission(FrontDeskPermission.WALK_IN_ORDER_VIEW).enabled(true).build()));
        when(overrideRepository.findAllByUserIds(List.of(6L, 5L))).thenReturn(List.of(
                FrontDeskUserPermissionOverride.builder().user(frontDeskUser).permission(FrontDeskPermission.STOCK_TRANSFER).allowed(true).build(),
                FrontDeskUserPermissionOverride.builder().user(frontDeskUser).permission(FrontDeskPermission.CUSTOMER_SEARCH).allowed(false).build(),
                FrontDeskUserPermissionOverride.builder().user(other).permission(FrontDeskPermission.CUSTOMER_SEARCH).allowed(true).build()));

        List<FrontDeskUserSummaryResponse> list = service.getFrontDeskUsers();

        assertEquals("Another", list.get(0).fullName());
        assertEquals(new FrontDeskUserSummaryResponse.PermissionSummary(2, 8, 0, 0, false), list.get(0).permissionSummary());
        assertEquals(new FrontDeskUserSummaryResponse.PermissionSummary(2, 8, 1, 1, true), list.get(1).permissionSummary());
        verify(overrideRepository, never()).findAllByUser(any(User.class));
    }

    @Test
    void updateUserPermissions_RejectsConflictingOverrides() {
        when(userRepository.lockById(5L)).thenReturn(Optional.of(frontDeskUser));

        assertThrows(BadRequestException.class, () -> service.updateUserPermissions(
                5L,
                new FrontDeskUserPermissionOverrideUpdateRequest(
                        Set.of(FrontDeskPermission.CUSTOMER_SEARCH),
                        Set.of(FrontDeskPermission.CUSTOMER_SEARCH)
                )
        ));
    }

    @Test
    void getUserPermissions_ReturnsTemplateOverridesAndEffectiveSet() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(frontDeskUser));
        when(templateRepository.findAllByEnabledTrue()).thenReturn(List.of(
                FrontDeskPermissionTemplate.builder().permission(FrontDeskPermission.WALK_IN_ORDER_CREATE).enabled(true).build()
        ));
        when(overrideRepository.findAllByUser(frontDeskUser)).thenReturn(List.of(
                FrontDeskUserPermissionOverride.builder()
                        .user(frontDeskUser)
                        .permission(FrontDeskPermission.CUSTOMER_SEARCH)
                        .allowed(true)
                        .build(),
                FrontDeskUserPermissionOverride.builder()
                        .user(frontDeskUser)
                        .permission(FrontDeskPermission.WALK_IN_ORDER_VIEW)
                        .allowed(false)
                        .build()
        ));
        when(frontDeskAccessService.getEffectivePermissions(frontDeskUser)).thenReturn(Set.of(
                FrontDeskPermission.WALK_IN_ORDER_CREATE,
                FrontDeskPermission.CUSTOMER_SEARCH
        ));

        FrontDeskUserPermissionsResponse response = service.getUserPermissions(5L);

        assertEquals(Set.of(FrontDeskPermission.WALK_IN_ORDER_CREATE), response.templatePermissions());
        assertEquals(Set.of(FrontDeskPermission.CUSTOMER_SEARCH), response.allowedOverrides());
        assertEquals(Set.of(FrontDeskPermission.WALK_IN_ORDER_VIEW), response.deniedOverrides());
        assertEquals(Set.of(FrontDeskPermission.WALK_IN_ORDER_CREATE, FrontDeskPermission.CUSTOMER_SEARCH), response.effectivePermissions());
    }
}
