package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A missing entitlement never grants (plans.md 3.3: off / 0), and a LIMIT key is never read as a BOOLEAN switch. */
class EntitlementSnapshotTest {

    private static final TenantPlanState STATE = new TenantPlanState(1, 1, SubscriptionStatus.ACTIVE, null);

    @Test
    void missingRowsAreOffAndZero() {
        EntitlementService.Snapshot s = new EntitlementService.Snapshot(STATE, new EnumMap<>(PlanFeature.class), false);
        for (PlanFeature f : PlanFeature.values()) {
            assertThat(s.enabled(f)).as(f.name()).isFalse();
        }
        assertThat(s.limit(PlanFeature.MAX_PRODUCTS)).isZero();
    }

    @Test
    void nullLimitIsUnlimited_andALimitKeyIsNotABooleanGrant() {
        Map<PlanFeature, EntitlementView> m = new EnumMap<>(PlanFeature.class);
        m.put(PlanFeature.MAX_STAFF, new EntitlementView("MAX_STAFF", true, null));
        m.put(PlanFeature.WALK_IN_POS, new EntitlementView("WALK_IN_POS", false, null));
        EntitlementService.Snapshot s = new EntitlementService.Snapshot(STATE, m, false);
        assertThat(s.limit(PlanFeature.MAX_STAFF)).isNull();
        assertThat(s.enabled(PlanFeature.MAX_STAFF)).isFalse();
        assertThat(s.enabled(PlanFeature.WALK_IN_POS)).isFalse();
    }
}
