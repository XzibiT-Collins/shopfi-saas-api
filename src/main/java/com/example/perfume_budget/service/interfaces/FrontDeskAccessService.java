package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.model.User;

import java.util.Set;

public interface FrontDeskAccessService {
    Set<FrontDeskPermission> getEffectivePermissions(User user);

    boolean hasPermission(User user, FrontDeskPermission permission);

    User requireAccess(FrontDeskPermission permission);
}
