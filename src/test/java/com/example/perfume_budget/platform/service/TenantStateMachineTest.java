package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.service.TenantStateMachine.Decision;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static com.example.perfume_budget.platform.enums.TenantAction.*;
import static com.example.perfume_budget.platform.enums.TenantStatus.*;
import static com.example.perfume_budget.platform.service.TenantStateMachine.Decision.*;
import static org.assertj.core.api.Assertions.assertThat;

/** PLAT-02: the documented transition table, checked cell by cell for every (action, status) pair. */
class TenantStateMachineTest {

    private static Map<TenantStatus, Decision> row(Decision pending, Decision provisioning, Decision active,
                                                   Decision suspended, Decision failed) {
        Map<TenantStatus, Decision> m = new EnumMap<>(TenantStatus.class);
        m.put(PENDING, pending);
        m.put(PROVISIONING, provisioning);
        m.put(ACTIVE, active);
        m.put(SUSPENDED, suspended);
        m.put(FAILED, failed);
        return m;
    }

    private static final Map<TenantAction, Map<TenantStatus, Decision>> TABLE = Map.of(
            APPROVE, row(TRANSITION, NO_OP, NO_OP, ILLEGAL, ILLEGAL),
            SUSPEND, row(ILLEGAL, ILLEGAL, TRANSITION, NO_OP, ILLEGAL),
            REACTIVATE, row(ILLEGAL, ILLEGAL, NO_OP, TRANSITION, ILLEGAL),
            REPROVISION, row(ILLEGAL, NO_OP, ILLEGAL, ILLEGAL, TRANSITION));

    @Test
    void everyActionStatusPairMatchesTheDocumentedTable() {
        for (TenantAction a : TenantAction.values()) {
            for (TenantStatus s : TenantStatus.values()) {
                assertThat(TenantStateMachine.decide(a, s)).as(a + " from " + s).isEqualTo(TABLE.get(a).get(s));
            }
        }
    }

    @Test
    void anActiveTenantIsNeverReprovisioned_andOnlyActiveCanBeSuspended() {
        assertThat(TenantStateMachine.decide(REPROVISION, ACTIVE)).isEqualTo(ILLEGAL);
        for (TenantStatus s : TenantStatus.values()) {
            if (s != ACTIVE && s != SUSPENDED) {
                assertThat(TenantStateMachine.decide(SUSPEND, s)).isEqualTo(ILLEGAL);
            }
        }
    }

    @Test
    void targets() {
        assertThat(TenantStateMachine.target(SUSPEND)).isEqualTo(SUSPENDED);
        assertThat(TenantStateMachine.target(REACTIVATE)).isEqualTo(ACTIVE);
        assertThat(TenantStateMachine.target(APPROVE)).isEqualTo(ACTIVE);
        assertThat(TenantStateMachine.target(REPROVISION)).isEqualTo(ACTIVE);
    }
}
