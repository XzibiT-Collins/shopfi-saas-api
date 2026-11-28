package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.platform.plan.PlanFeature;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The two 402 bodies of docs/api/plans.md section 14 (exact codes, keys and descriptions). */
public final class EntitlementErrors {

    public static final String PLAN_UPGRADE_REQUIRED = "PLAN_UPGRADE_REQUIRED";
    public static final String PLAN_LIMIT_REACHED = "PLAN_LIMIT_REACHED";
    public static final String NOT_IN_PLAN = "NOT_IN_PLAN";
    public static final String SUBSCRIPTION_INACTIVE = "SUBSCRIPTION_INACTIVE";
    public static final String NOT_IN_PLAN_MESSAGE = "Your plan does not include this feature.";
    public static final String INACTIVE_MESSAGE =
            "Your subscription is not active. Existing data stays readable; upgrade to make changes.";
    public static final String LIMIT_MESSAGE = "You have reached your plan's limit for this feature.";

    private EntitlementErrors() {
    }

    public static ApiCodeException notInPlan(PlanFeature feature, List<String> requiredPlans) {
        return upgrade(feature, NOT_IN_PLAN, NOT_IN_PLAN_MESSAGE, requiredPlans);
    }

    public static ApiCodeException inactive(PlanFeature feature, List<String> requiredPlans) {
        return upgrade(feature, SUBSCRIPTION_INACTIVE, INACTIVE_MESSAGE, requiredPlans);
    }

    public static ApiCodeException limitReached(PlanFeature feature, int limit, long used, List<String> requiredPlans) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("feature", feature.name());
        extra.put("limit", limit);
        extra.put("used", used);
        extra.put("requiredPlans", List.copyOf(requiredPlans));
        return new ApiCodeException(HttpStatus.PAYMENT_REQUIRED, PLAN_LIMIT_REACHED, LIMIT_MESSAGE, extra);
    }

    private static ApiCodeException upgrade(PlanFeature feature, String reason, String message, List<String> requiredPlans) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("feature", feature.name());
        extra.put("reason", reason);
        extra.put("requiredPlans", List.copyOf(requiredPlans));
        return new ApiCodeException(HttpStatus.PAYMENT_REQUIRED, PLAN_UPGRADE_REQUIRED, message, extra);
    }
}
