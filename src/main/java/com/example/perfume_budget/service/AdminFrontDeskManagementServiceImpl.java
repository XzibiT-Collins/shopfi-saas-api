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
import com.example.perfume_budget.utils.ConcurrencyTag;
import com.example.perfume_budget.validation.PasswordPolicy;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        return templateResponse(getTemplatePermissions());
    }

    @Override
    public FrontDeskPermissionTemplateResponse updateDefaultTemplate(FrontDeskPermissionTemplateUpdateRequest request) {
        return updateDefaultTemplate(request, null);
    }

    @Override
    public FrontDeskPermissionTemplateResponse updateDefaultTemplate(FrontDeskPermissionTemplateUpdateRequest request,
                                                                     String ifMatch) {
        String expected = ConcurrencyTag.parseIfMatch(ifMatch);
        // serialise this tenant's template saves, then compare the caller's ETag with the state read under the lock
        templateRepository.lockTemplateSaves();
        ConcurrencyTag.require(expected, templateVersion(getTemplatePermissions()), "TEMPLATE_VERSION_CONFLICT",
                "The default permissions were changed by someone else. Reload and try again.");
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
        templateRepository.flush();

        return templateResponse(getTemplatePermissions());
    }

    @Override
    public List<FrontDeskUserSummaryResponse> getFrontDeskUsers() {
        List<User> users = userRepository.findAllByRoles(UserRole.FRONT_DESK).stream()
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (users.isEmpty()) {
            return List.of();
        }
        // API-RD-11d: two queries for the whole list (template once, every user's overrides once), not one per user
        Set<FrontDeskPermission> template = getTemplatePermissions();
        Map<Long, List<FrontDeskUserPermissionOverride>> overridesByUser = overrideRepository
                .findAllByUserIds(users.stream().map(User::getId).toList()).stream()
                .collect(Collectors.groupingBy(o -> o.getUser().getId()));
        return users.stream()
                .map(user -> toSummary(user, summarise(template, overridesByUser.getOrDefault(user.getId(), List.of()))))
                .toList();
    }

    @Override
    public FrontDeskUserSummaryResponse createFrontDeskUser(CreateFrontDeskUserRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("An account with this email already exists.", "EMAIL_ALREADY_EXISTS");
        }
        requireAcceptablePassword(request.password());

        User user = User.builder()
                .fullName(normalizeRequiredText(request.fullName(), "Full name is required.", "FULL_NAME_REQUIRED"))
                .email(normalizedEmail)
                .password(passwordEncoder.encode(PasswordPolicy.normalize(request.password())))
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
            user.setFullName(normalizeRequiredText(request.fullName(), "Full name cannot be blank.", "FULL_NAME_REQUIRED"));
        }
        if (request.email() != null) {
            String normalizedEmail = normalizeEmail(request.email());
            userRepository.findByEmail(normalizedEmail)
                    .filter(existing -> !existing.getId().equals(user.getId()))
                    .ifPresent(existing -> {
                        throw new DuplicateResourceException("An account with this email already exists.", "EMAIL_ALREADY_EXISTS");
                    });
            user.setEmail(normalizedEmail);
        }
        if (request.password() != null && !request.password().isBlank()) {
            requireAcceptablePassword(request.password());
            user.setPassword(passwordEncoder.encode(PasswordPolicy.normalize(request.password())));
        }
        if (request.isActive() != null) {
            user.setActive(request.isActive());
        }

        return toSummary(userRepository.save(user));
    }

    /**
     * API-RD-11b: staff passwords follow the customer policy ({@link PasswordPolicy}: 8+ characters, at most 72 bytes,
     * an uppercase letter, a digit and a special character), evaluated on the stripped value that is stored. The
     * too-short text stays the historic one (no full stop) because the admin UI matches it.
     */
    private void requireAcceptablePassword(String password) {
        String violation = PasswordPolicy.violation(password);
        if (violation == null) {
            return;
        }
        if (violation.equals(PasswordPolicy.MSG_TOO_SHORT)) {
            throw new BadRequestException("Password must be at least 8 characters long", "PASSWORD_TOO_SHORT");
        }
        throw new BadRequestException(violation, violation.equals(PasswordPolicy.MSG_TOO_LONG)
                ? "PASSWORD_TOO_LONG" : "PASSWORD_TOO_WEAK");
    }

    @Override
    public FrontDeskUserPermissionsResponse getUserPermissions(Long userId) {
        User user = getFrontDeskUser(userId);
        List<FrontDeskUserPermissionOverride> overrides = overrideRepository.findAllByUser(user);
        return buildPermissionResponse(user, overrides);
    }

    @Override
    public FrontDeskUserPermissionsResponse updateUserPermissions(Long userId, FrontDeskUserPermissionOverrideUpdateRequest request) {
        return updateUserPermissions(userId, request, null);
    }

    @Override
    public FrontDeskUserPermissionsResponse updateUserPermissions(Long userId, FrontDeskUserPermissionOverrideUpdateRequest request,
                                                                  String ifMatch) {
        String expected = ConcurrencyTag.parseIfMatch(ifMatch);
        // row lock on the user serialises this user's permission saves; the ETag is compared with the state read under it
        User user = userRepository.lockById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found.", "USER_NOT_FOUND"));
        requireFrontDesk(user);
        Set<FrontDeskPermission> allowedPermissions = normalizePermissionSet(request.allowedPermissions());
        Set<FrontDeskPermission> deniedPermissions = normalizePermissionSet(request.deniedPermissions());

        validateNoConflictingOverrides(allowedPermissions, deniedPermissions);

        List<FrontDeskUserPermissionOverride> existingOverrides = overrideRepository.findAllByUser(user);
        ConcurrencyTag.require(expected, overridesVersion(user.getId(), existingOverrides),
                "USER_PERMISSIONS_VERSION_CONFLICT",
                "This staff member's permissions were changed by someone else. Reload and try again.");
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
                frontDeskAccessService.getEffectivePermissions(user),
                overridesVersion(user.getId(), overrides)
        );
    }

    private FrontDeskPermissionTemplateResponse templateResponse(Set<FrontDeskPermission> enabled) {
        return new FrontDeskPermissionTemplateResponse(enabled, templateVersion(enabled));
    }

    static String templateVersion(Set<FrontDeskPermission> enabled) {
        return ConcurrencyTag.of("fd-template|" + sortedNames(enabled));
    }

    static String overridesVersion(Long userId, List<FrontDeskUserPermissionOverride> overrides) {
        Set<FrontDeskPermission> allowed = EnumSet.noneOf(FrontDeskPermission.class);
        Set<FrontDeskPermission> denied = EnumSet.noneOf(FrontDeskPermission.class);
        for (FrontDeskUserPermissionOverride o : overrides) {
            (o.isAllowed() ? allowed : denied).add(o.getPermission());
        }
        return ConcurrencyTag.of("fd-user|" + userId + "|allow=" + sortedNames(allowed) + "|deny=" + sortedNames(denied));
    }

    private static String sortedNames(Set<FrontDeskPermission> permissions) {
        return permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /** API-RD-11d: effective = template + allowed - denied; no-op overrides (already granted / already absent) do not count. */
    private static FrontDeskUserSummaryResponse.PermissionSummary summarise(
            Set<FrontDeskPermission> template, List<FrontDeskUserPermissionOverride> overrides) {
        Set<FrontDeskPermission> effective = EnumSet.noneOf(FrontDeskPermission.class);
        effective.addAll(template);
        for (FrontDeskUserPermissionOverride o : overrides) {
            if (o.isAllowed()) {
                effective.add(o.getPermission());
            } else {
                effective.remove(o.getPermission());
            }
        }
        Set<FrontDeskPermission> granted = EnumSet.noneOf(FrontDeskPermission.class);
        granted.addAll(effective);
        granted.removeAll(template);
        Set<FrontDeskPermission> removed = EnumSet.noneOf(FrontDeskPermission.class);
        removed.addAll(template);
        removed.removeAll(effective);
        return new FrontDeskUserSummaryResponse.PermissionSummary(effective.size(), FrontDeskPermission.values().length,
                granted.size(), removed.size(), !granted.isEmpty() || !removed.isEmpty());
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
            throw new BadRequestException("A permission cannot be both allowed and denied.", "PERMISSION_CONFLICT");
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
                .orElseThrow(() -> new ResourceNotFoundException("User not found.", "USER_NOT_FOUND"));
    }

    private User getFrontDeskUser(Long userId) {
        User user = getUser(userId);
        requireFrontDesk(user);
        return user;
    }

    private void requireFrontDesk(User user) {
        if (user.getRoles() != UserRole.FRONT_DESK) {
            throw new BadRequestException("Selected user is not a front desk user.", "NOT_FRONT_DESK_USER");
        }
    }

    private String normalizeRequiredText(String value, String errorMessage, String code) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(errorMessage, code);
        }
        return value.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Email is required.", "EMAIL_REQUIRED");
        }
        return email.trim().toLowerCase();
    }

    /** Single-user answers (create, assign, update): the same summary, computed for this one user. */
    private FrontDeskUserSummaryResponse toSummary(User user) {
        return toSummary(user, summarise(getTemplatePermissions(), overrideRepository.findAllByUser(user)));
    }

    private FrontDeskUserSummaryResponse toSummary(User user, FrontDeskUserSummaryResponse.PermissionSummary summary) {
        return new FrontDeskUserSummaryResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.isActive(),
                user.getRoles(),
                user.getRoles() == UserRole.FRONT_DESK ? summary : null
        );
    }
}
