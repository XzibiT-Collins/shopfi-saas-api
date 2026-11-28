package com.example.perfume_budget.entitlement;

import java.util.List;

/**
 * Request paths that are never plan-gated and never blocked by read-only mode (plans.md 18 item 10, billing.md 17.2): the
 * member has to be able to pay and cancel while restricted. {@link EntitlementService} refuses (loudly, as a programming
 * error) to run a plan check inside a request to one of them, and the source scan test checks every controller mapped there.
 */
public final class NeverGatedPaths {

    public static final List<String> PREFIXES = List.of(
            "/api/v1/admin/billing",
            "/api/v1/platform/billing",
            "/api/v1/platform/onboarding/card",
            "/api/v1/dev/billing");

    private NeverGatedPaths() {
    }

    /** True when {@code path} (no context path, no query) is one of the prefixes or below it. */
    public static boolean matches(String path) {
        if (path == null) {
            return false;
        }
        for (String p : PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }
}
