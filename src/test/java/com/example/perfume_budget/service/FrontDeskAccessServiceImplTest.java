package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.FrontDeskPermissionTemplate;
import com.example.perfume_budget.model.FrontDeskUserPermissionOverride;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.FrontDeskPermissionTemplateRepository;
import com.example.perfume_budget.repository.FrontDeskUserPermissionOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FrontDeskAccessServiceImplTest {

    @Mock
    private FrontDeskPermissionTemplateRepository templateRepository;
    @Mock
    private FrontDeskUserPermissionOverrideRepository overrideRepository;

    @InjectMocks
    private FrontDeskAccessServiceImpl frontDeskAccessService;

    private User frontDeskUser;

    @BeforeEach
    void setUp() {
        frontDeskUser = User.builder()
                .id(10L)
                .email("frontdesk@example.com")
                .fullName("Front Desk")
                .roles(UserRole.FRONT_DESK)
                .build();
    }

    @Test
    void getEffectivePermissions_ReturnsEmptyForNonFrontDeskUser() {
        User customer = User.builder().roles(UserRole.CUSTOMER).build();

        Set<FrontDeskPermission> result = frontDeskAccessService.getEffectivePermissions(customer);

        assertTrue(result.isEmpty());
    }

    @Test
    void getEffectivePermissions_MergesTemplateAndOverrides() {
        when(templateRepository.findAllByEnabledTrue()).thenReturn(List.of(
                FrontDeskPermissionTemplate.builder()
                        .permission(FrontDeskPermission.WALK_IN_ORDER_CREATE)
                        .enabled(true)
                        .build(),
                FrontDeskPermissionTemplate.builder()
                        .permission(FrontDeskPermission.CUSTOMER_SEARCH)
                        .enabled(true)
                        .build()
        ));
        when(overrideRepository.findAllByUser(frontDeskUser)).thenReturn(List.of(
                FrontDeskUserPermissionOverride.builder()
                        .user(frontDeskUser)
                        .permission(FrontDeskPermission.CUSTOMER_SEARCH)
                        .allowed(false)
                        .build(),
                FrontDeskUserPermissionOverride.builder()
                        .user(frontDeskUser)
                        .permission(FrontDeskPermission.WALK_IN_ORDER_VIEW)
                        .allowed(true)
                        .build()
        ));

        Set<FrontDeskPermission> result = frontDeskAccessService.getEffectivePermissions(frontDeskUser);

        assertEquals(Set.of(
                FrontDeskPermission.WALK_IN_ORDER_CREATE,
                FrontDeskPermission.WALK_IN_ORDER_VIEW
        ), result);
    }

    @Test
    void hasPermission_ReturnsTrueForAdminWithoutFrontDeskResolution() {
        User admin = User.builder().roles(UserRole.ADMIN).build();

        assertTrue(frontDeskAccessService.hasPermission(admin, FrontDeskPermission.WALK_IN_ORDER_CREATE));
    }

    @Test
    void hasPermission_ReturnsFalseWhenFrontDeskPermissionMissing() {
        when(templateRepository.findAllByEnabledTrue()).thenReturn(List.of());
        when(overrideRepository.findAllByUser(frontDeskUser)).thenReturn(List.of());

        assertFalse(frontDeskAccessService.hasPermission(frontDeskUser, FrontDeskPermission.WALK_IN_ORDER_CREATE));
    }
}
