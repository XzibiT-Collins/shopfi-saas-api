package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.exception.ApiCodeException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** The coded plan errors of docs/api/plans.md and errors.md (exact texts and codes). */
public final class PlanErrors {

    public static final String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";
    public static final String PLAN_CODE_TAKEN = "PLAN_CODE_TAKEN";
    public static final String PLAN_VERSION_CONFLICT = "PLAN_VERSION_CONFLICT";
    public static final String PLAN_INACTIVE = "PLAN_INACTIVE";
    public static final String TRIAL_NOT_STARTED = "TRIAL_NOT_STARTED";

    private PlanErrors() {
    }

    public static ApiCodeException notFound() {
        return new ApiCodeException(HttpStatus.NOT_FOUND, PLAN_NOT_FOUND, "Plan not found");
    }

    public static ApiCodeException codeTaken() {
        return new ApiCodeException(HttpStatus.CONFLICT, PLAN_CODE_TAKEN, "A plan with this code already exists");
    }

    public static ApiCodeException inactive() {
        return new ApiCodeException(HttpStatus.BAD_REQUEST, PLAN_INACTIVE,
                "The plan is inactive; send allowInactive to assign it anyway");
    }

    /** PO decision on QA L3: {@code trialEndsAt} cannot be set before provisioning has started the trial. */
    public static ApiCodeException trialNotStarted() {
        return new ApiCodeException(HttpStatus.BAD_REQUEST, TRIAL_NOT_STARTED,
                "The trial starts when the store has been provisioned; it cannot be set before that");
    }

    /** One entry per stale plan of the request; the dedicated 409, never the generic 503 TRY_AGAIN. */
    public static ApiCodeException versionConflict(List<Map<String, Object>> conflicts) {
        return new ApiCodeException(HttpStatus.CONFLICT, PLAN_VERSION_CONFLICT,
                "Another admin changed these plans. Reload them and apply your changes again.",
                Map.of("conflicts", conflicts));
    }
}
