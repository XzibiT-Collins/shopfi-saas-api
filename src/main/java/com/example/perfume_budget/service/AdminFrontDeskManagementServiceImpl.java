package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.front_desk.request.CreateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskPermissionTemplateUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.FrontDeskUserPermissionOverrideUpdateRequest;
import com.example.perfume_budget.dto.front_desk.request.UpdateFrontDeskUserRequest;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskPermissionTemplateResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserPermissionsResponse;
import com.example.perfume_budget.dto.front_desk.response.FrontDeskUserSummaryResponse;
import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.FrontDeskPermissionTemplate;
import com.example.perfume_budget.model.FrontDeskUserPermissionOverride;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.FrontDeskPermissionTemplateRepository;
import com.example.perfume_budget.repository.FrontDeskUserPermissionOverrideRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.service.interfaces.AdminFrontDeskManagementService;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminFrontDeskManagementServiceImpl implements AdminFrontDeskManagementService {

    private final FrontDeskPermissionTemplateRepository templateRepository;
    private final FrontDeskUserPermissionOverrideRepository overrideRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FrontDeskAccessService frontDeskAccessService;

    @Override
    public FrontDeskPermissionTemplateResponse getDefaultTemplate() {
        return new FrontDeskPermissionTemplateResponse(getTemplatePermissions());
    }

    @Override
    public FrontDeskPermissionTemplateResponse updateDefaultTemplate(FrontDeskPermissionTemplateUpdateRequest request) {
        Set<FrontDeskPermission> requestedPermissions = normalizePermissionSet(request.permissions());
        List<FrontDeskPermissionTemplate> existingTemplates = templateRepository.findAll();

        for (FrontDeskPermission permission : FrontDeskPermission.values()) {
            FrontDeskPermissionTemplate template = existingTemplates.stream()
                    .filter(item -> item.getPermission() == permission)
                    .findFirst()
                    .orElseGet(() -> FrontDeskPermissionTemplate.builder()
                            .permission(permission)
                            .build());
            template.setEnabled(requestedPermissions.contains(permission));
            templateRepository.save(template);
        }

        return new FrontDeskPermissionTemplateResponse(getTemplatePermissions());
    }

    @Override
    public List<FrontDeskUserSummaryResponse> getFrontDeskUsers() {
        return userRepository.findAllByRoles(UserRole.FRONT_DESK).stream()
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toSummary)
                .toList();
    }

    @Override
    public FrontDeskUserSummaryResponse createFrontDeskUser(CreateFrontDeskUserRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("An account with this email already exists.");
        }

        User user = User.builder()
                .fullName(normalizeRequiredText(request.fullName(), "Full name is required."))
                .email(normalizedEmail)
                .password(passwordEncoder.encode(request.password().trim()))
                .roles(UserRole.FRONT_DESK)
                .isActive(request.isActive())
                .emailVerified(true)
                .authProvider(AuthProvider.LOCAL)
                .build();

        return toSummary(userRepository.save(user));
    }

    @Override
    public FrontDeskUserSummaryResponse assignUserToFrontDesk(Long userId) {
        User user = getUser(userId);
        user.setRoles(UserRole.FRONT_DESK);
        return toSummary(userRepository.save(user));
    }

    @Override
    public FrontDeskUserSummaryResponse updateFrontDeskUser(Long userId, UpdateFrontDeskUserRequest request) {
        User user = getFrontDeskUser(userId);

        if (request.fullName() != null) {
            user.setFullName(normalizeRequiredText(request.fullName(), "Full name cannot be blank."));
        }
        if (request.email() != null) {
            String normalizedEmail = normalizeEmail(request.email());
            userRepository.findByEmail(normalizedEmail)
                    .filter(existing -> !existing.getId().equals(user.getId()))
                    .ifPresent(existing -> {
                        throw new DuplicateResourceException("An account with this email already exists.");
                    });
            user.setEmail(normalizedEmail);
        }
        validateOptionalPassword(request.password());
        if (request.password() != null && !request.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.password().trim()));
        }
        if (request.isActive() != null) {
            user.setActive(request.isActive());
        }

        return toSummary(userRepository.save(user));
    }

    private void validateOptionalPassword(String password) {
        if (password != null && !password.isBlank() && password.trim().length() < 8) {
            throw new BadRequestException("Password must be at least 8 characters long");
        }
    }

    @Override
    public FrontDeskUserPermissionsResponse getUserPermissions(Long userId) {
        User user = getFrontDeskUser(userId);
        List<FrontDeskUserPermissionOverride> overrides = overrideRepository.findAllByUser(user);
        return buildPermissionResponse(user, overrides);
    }

    @Override
    public FrontDeskUserPermissionsResponse updateUserPermissions(Long userId, FrontDeskUserPermissionOverrideUpdateRequest request) {
        User user = getFrontDeskUser(userId);
        Set<FrontDeskPermission> allowedPermissions = normalizePermissionSet(request.allowedPermissions());
        Set<FrontDeskPermission> deniedPermissions = normalizePermissionSet(request.deniedPermissions());

        validateNoConflictingOverrides(allowedPermissions, deniedPermissions);

        List<FrontDeskUserPermissionOverride> existingOverrides = overrideRepository.findAllByUser(user);
        if (!existingOverrides.isEmpty()) {
            overrideRepository.deleteAll(existingOverrides);
            // Force the deletes to hit the DB before the inserts below. Hibernate orders
            // inserts ahead of deletes within a single flush, so re-saving a permission the
            // user already has would otherwise violate uk_front_desk_user_permission_override.
            overrideRepository.flush();
        }

        List<FrontDeskUserPermissionOverride> newOverrides = allowedPermissions.stream()
                .map(permission -> FrontDeskUserPermissionOverride.builder()
                        .user(user)
                        .permission(permission)
                        .allowed(true)
                        .build())
                .toList();
        List<FrontDeskUserPermissionOverride> deniedOverridesToSave = deniedPermissions.stream()
                .map(permission -> FrontDeskUserPermissionOverride.builder()
                        .user(user)
                        .permission(permission)
                        .allowed(false)
                        .build())
                .toList();

        overrideRepository.saveAll(newOverrides);
        List<FrontDeskUserPermissionOverride> savedOverrides = overrideRepository.saveAll(deniedOverridesToSave);

        return buildPermissionResponse(user, concatOverrides(newOverrides, savedOverrides));
    }

    private FrontDeskUserPermissionsResponse buildPermissionResponse(User user, List<FrontDeskUserPermissionOverride> overrides) {
        Set<FrontDeskPermission> templatePermissions = getTemplatePermissions();
        Set<FrontDeskPermission> allowedOverrides = overrides.stream()
                .filter(FrontDeskUserPermissionOverride::isAllowed)
                .map(FrontDeskUserPermissionOverride::getPermission)
                .collect(() -> EnumSet.noneOf(FrontDeskPermission.class), EnumSet::add, EnumSet::addAll);
        Set<FrontDeskPermission> deniedOverrides = overrides.stream()
                .filter(override -> !override.isAllowed())
                .map(FrontDeskUserPermissionOverride::getPermission)
                .collect(() -> EnumSet.noneOf(FrontDeskPermission.class), EnumSet::add, EnumSet::addAll);

        return new FrontDeskUserPermissionsResponse(
                user.getId(),
                templatePermissions,
                allowedOverrides,
                deniedOverrides,
                frontDeskAccessService.getEffectivePermissions(user)
        );
    }

    private List<FrontDeskUserPermissionOverride> concatOverrides(List<FrontDeskUserPermissionOverride> allowed,
                                                                  List<FrontDeskUserPermissionOverride> denied) {
        return java.util.stream.Stream.concat(allowed.stream(), denied.stream()).toList();
    }

    private void validateNoConflictingOverrides(Set<FrontDeskPermission> allowed, Set<FrontDeskPermission> denied) {
        if (allowed.isEmpty() || denied.isEmpty()) {
            return;
        }
        Set<FrontDeskPermission> overlap = EnumSet.copyOf(allowed);
        overlap.retainAll(denied);
        if (!overlap.isEmpty()) {
            throw new BadRequestException("A permission cannot be both allowed and denied.");
        }
    }

    private Set<FrontDeskPermission> getTemplatePermissions() {
        return templateRepository.findAllByEnabledTrue().stream()
                .map(FrontDeskPermissionTemplate::getPermission)
                .collect(() -> EnumSet.noneOf(FrontDeskPermission.class), EnumSet::add, EnumSet::addAll);
    }

    private Set<FrontDeskPermission> normalizePermissionSet(Set<FrontDeskPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return EnumSet.noneOf(FrontDeskPermission.class);
        }
        return EnumSet.copyOf(permissions);
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    private User getFrontDeskUser(Long userId) {
        User user = getUser(userId);
        if (user.getRoles() != UserRole.FRONT_DESK) {
            throw new BadRequestException("Selected user is not a front desk user.");
        }
        return user;
    }

    private String normalizeRequiredText(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(errorMessage);
        }
        return value.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Email is required.");
        }
        return email.trim().toLowerCase();
    }

    private FrontDeskUserSummaryResponse toSummary(User user) {
        return new FrontDeskUserSummaryResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.isActive(),
                user.getRoles()
        );
    }
}
