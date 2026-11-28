package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
                .containsExactly("tenantId", "planId", "status", "trialEndsAt", "accessState", "billingManaged"); // BILL-01c: still no readOnly
    }

    // ------------------------------------------------------------------------------------------ BILL-01c AC3

    private static boolean ro(SubscriptionStatus s, Instant trialEnd, Instant now, AccessState a, boolean managed) {
        return new TenantPlanState(1, 1, s, trialEnd, a, managed).readOnlyAt(now);
    }

    @org.junit.jupiter.api.Test
    void billingManaged_onlyTheAccessStateDecides() {
        for (SubscriptionStatus s : SubscriptionStatus.values()) {
            // a trial that ended or a CANCELED status is a charge / a billing event, not a restriction, once billing manages the tenant
            assertThat(ro(s, END.minusSeconds(1), END, AccessState.FULL, true)).as("%s FULL managed", s).isFalse();
            assertThat(ro(s, END.minusSeconds(1), END, AccessState.GRACE, true)).as("%s GRACE managed", s).isFalse();
            assertThat(ro(s, END.plusSeconds(99), END, AccessState.READ_ONLY, true)).as("%s READ_ONLY managed", s).isTrue();
        }
    }

    @org.junit.jupiter.api.Test
    void readOnlyAccessStateRestrictsWhetherOrNotBillingManaged() {
        for (SubscriptionStatus s : SubscriptionStatus.values()) {
            assertThat(ro(s, null, END, AccessState.READ_ONLY, false)).isTrue();
        }
    }

    @org.junit.jupiter.api.Test
    void notBillingManaged_thePreBillingRuleOfPlansMd13_isUnchanged() {
        for (AccessState a : List.of(AccessState.FULL, AccessState.GRACE)) {
            assertThat(ro(SubscriptionStatus.CANCELED, null, END, a, false)).isTrue();
            assertThat(ro(SubscriptionStatus.TRIALING, END, END, a, false)).as("trial ends exactly now").isTrue();
            assertThat(ro(SubscriptionStatus.TRIALING, END.plusSeconds(1), END, a, false)).isFalse();
            assertThat(ro(SubscriptionStatus.TRIALING, null, END, a, false)).isFalse();
            assertThat(ro(SubscriptionStatus.ACTIVE, END.minusSeconds(99), END, a, false)).isFalse();
            assertThat(ro(SubscriptionStatus.PAST_DUE, END.minusSeconds(99), END, a, false)).isFalse();
        }
    }

    @org.junit.jupiter.api.Test
    void theFourArgumentConstructorIsPreBilling_andAMissingAccessStateReadsAsFull() {
        TenantPlanState legacy = new TenantPlanState(1, 1, SubscriptionStatus.ACTIVE, null);
        assertThat(legacy.accessState()).isEqualTo(AccessState.FULL);
        assertThat(legacy.billingManaged()).isFalse();
        assertThat(new TenantPlanState(1, 1, SubscriptionStatus.ACTIVE, null, null, true).accessState()).isEqualTo(AccessState.FULL);
    }

    @org.junit.jupiter.api.Test
    void anEntryCachedBeforeBillingStillDeserialises_asFullAndNotManaged() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        TenantPlanState old = m.readValue("{\"tenantId\":7,\"planId\":2,\"status\":\"ACTIVE\",\"trialEndsAt\":null}", TenantPlanState.class);
        assertThat(old).isEqualTo(new TenantPlanState(7, 2, SubscriptionStatus.ACTIVE, null, AccessState.FULL, false));
    }
}
