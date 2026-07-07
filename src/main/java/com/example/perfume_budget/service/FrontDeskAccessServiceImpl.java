package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ForbiddenException;
import com.example.perfume_budget.model.FrontDeskPermissionTemplate;
import com.example.perfume_budget.model.FrontDeskUserPermissionOverride;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.FrontDeskPermissionTemplateRepository;
import com.example.perfume_budget.repository.FrontDeskUserPermissionOverrideRepository;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.utils.AuthUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FrontDeskAccessServiceImpl implements FrontDeskAccessService {

    private final FrontDeskPermissionTemplateRepository templateRepository;
    private final FrontDeskUserPermissionOverrideRepository overrideRepository;
    private final AuthUserUtil authUserUtil;

    @Override
    public Set<FrontDeskPermission> getEffectivePermissions(User user) {
        if (user == null || user.getRoles() != UserRole.FRONT_DESK) {
            return Collections.emptySet();
        }

        EnumSet<FrontDeskPermission> effectivePermissions = templateRepository.findAllByEnabledTrue().stream()
                .map(FrontDeskPermissionTemplate::getPermission)
                .collect(() -> EnumSet.noneOf(FrontDeskPermission.class), EnumSet::add, EnumSet::addAll);

        for (FrontDeskUserPermissionOverride override : overrideRepository.findAllByUser(user)) {
            if (override.isAllowed()) {
                effectivePermissions.add(override.getPermission());
            } else {
                effectivePermissions.remove(override.getPermission());
            }
        }

        return Collections.unmodifiableSet(effectivePermissions);
    }

    @Override
    public boolean hasPermission(User user, FrontDeskPermission permission) {
        if (user == null) {
            return false;
        }
        if (user.getRoles() == UserRole.ADMIN) {
            return true;
        }
        if (user.getRoles() != UserRole.FRONT_DESK) {
            return false;
        }
        return getEffectivePermissions(user).contains(permission);
    }

    @Override
    public User requireAccess(FrontDeskPermission permission) {
        User currentUser = authUserUtil.getCurrentUser();
        if (currentUser == null) {
            throw new BadRequestException("Authenticated user is required.");
        }
        if (currentUser.getRoles() == UserRole.ADMIN) {
            return currentUser;
        }
        if (currentUser.getRoles() == UserRole.FRONT_DESK && hasPermission(currentUser, permission)) {
            return currentUser;
        }
        throw new ForbiddenException("You do not have permission to perform this action.");
    }
}
