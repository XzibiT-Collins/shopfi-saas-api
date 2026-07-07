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
        when(passwordEncoder.encode("secret123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FrontDeskUserSummaryResponse response = service.createFrontDeskUser(
                new CreateFrontDeskUserRequest("Front Desk", "frontdesk@example.com", "secret123", true)
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
                new CreateFrontDeskUserRequest("Front Desk", "frontdesk@example.com", "secret123", true)
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
    void updateUserPermissions_RejectsConflictingOverrides() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(frontDeskUser));

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
