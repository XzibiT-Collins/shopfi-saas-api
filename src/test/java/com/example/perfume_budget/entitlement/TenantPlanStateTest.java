package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** plans.md 13: readOnly = CANCELED, or TRIALING with now >= trialEndsAt (the instant itself has ended the trial). */
class TenantPlanStateTest {

    private static final Instant END = Instant.parse("2030-06-01T12:00:00Z");

    private static boolean ro(SubscriptionStatus s, Instant end, Instant now) {
        return new TenantPlanState(1, 1, s, end).readOnlyAt(now);
    }

    @Test
    void trialingIsReadOnlyFromTheTrialEndInstantOn() {
        assertThat(ro(SubscriptionStatus.TRIALING, END, END.minusSeconds(1))).isFalse();
        assertThat(ro(SubscriptionStatus.TRIALING, END, END.minusNanos(1))).isFalse();
        assertThat(ro(SubscriptionStatus.TRIALING, END, END)).isTrue();
        assertThat(ro(SubscriptionStatus.TRIALING, END, END.plusSeconds(1))).isTrue();
        assertThat(ro(SubscriptionStatus.TRIALING, null, END)).as("trial not started").isFalse();
    }

    @Test
    void canceledAlways_activeAndPastDueNever() {
        assertThat(ro(SubscriptionStatus.CANCELED, null, END)).isTrue();
        assertThat(ro(SubscriptionStatus.CANCELED, END.plusSeconds(99), END)).isTrue();
        assertThat(ro(SubscriptionStatus.ACTIVE, END.minusSeconds(99), END)).isFalse();
        assertThat(ro(SubscriptionStatus.PAST_DUE, END.minusSeconds(99), END)).isFalse();
    }

    @Test
    void theCachedStateHasNoReadOnlyField() {
        assertThat(java.util.Arrays.stream(TenantPlanState.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("tenantId", "planId", "status", "trialEndsAt");
    }
}
