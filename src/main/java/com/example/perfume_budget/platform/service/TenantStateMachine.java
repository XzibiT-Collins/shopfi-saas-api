package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantStatus;

/**
 * The explicit tenant lifecycle state machine for platform-admin actions (PLAT-02). Pure and table-driven so it can
 * be exhaustively tested; the service applies it to the CURRENT row state with compare-and-set writes.
 *
 * <pre>
 * action      from        result
 * ----------  ----------  --------------------------------------------------------------------------
 * APPROVE     PENDING     TRANSITION  (runs the PROV-01 saga: PENDING -> PROVISIONING -> ACTIVE | FAILED)
 *             ACTIVE      NO_OP       (idempotent: already approved)
 *             PROVISIONING NO_OP      (idempotent: saga already running)
 *             SUSPENDED, FAILED       ILLEGAL (409)  (use REACTIVATE / REPROVISION)
 * SUSPEND     ACTIVE      TRANSITION  (ACTIVE -> SUSPENDED)
 *             SUSPENDED   NO_OP       (idempotent)
 *             PENDING, PROVISIONING, FAILED  ILLEGAL (409)
 * REACTIVATE  SUSPENDED   TRANSITION  (SUSPENDED -> ACTIVE)
 *             ACTIVE      NO_OP       (idempotent)
 *             PENDING, PROVISIONING, FAILED  ILLEGAL (409)
 * REPROVISION FAILED      TRANSITION  (FAILED -> PROVISIONING -> ACTIVE | FAILED)
 *             PROVISIONING NO_OP      (idempotent: saga already running; a saga stuck longer than the
 *                                      configured threshold is first reclaimed to FAILED by the service)
 *             PENDING, ACTIVE, SUSPENDED     ILLEGAL (409)  (an ACTIVE tenant is never re-provisioned)
 * </pre>
 * A NO_OP changes no tenant status and writes no audit row, with one exception: an APPROVE no-op on an ACTIVE tenant may
 * reconcile leftovers of an earlier crash (delete the signup hash, append a RECONCILE audit row), see PlatformTenantService.
 * Unknown tenants are a 404 before the machine is consulted.
 */
public final class TenantStateMachine {

    public enum Decision { TRANSITION, NO_OP, ILLEGAL }

    private TenantStateMachine() {
    }

    public static Decision decide(TenantAction action, TenantStatus current) {
        return switch (action) {
            case APPROVE -> switch (current) {
                case PENDING -> Decision.TRANSITION;
                case ACTIVE, PROVISIONING -> Decision.NO_OP;
                case SUSPENDED, FAILED -> Decision.ILLEGAL;
            };
            case SUSPEND -> switch (current) {
                case ACTIVE -> Decision.TRANSITION;
                case SUSPENDED -> Decision.NO_OP;
                case PENDING, PROVISIONING, FAILED -> Decision.ILLEGAL;
            };
            case REACTIVATE -> switch (current) {
                case SUSPENDED -> Decision.TRANSITION;
                case ACTIVE -> Decision.NO_OP;
                case PENDING, PROVISIONING, FAILED -> Decision.ILLEGAL;
            };
            case REPROVISION -> switch (current) {
                case FAILED -> Decision.TRANSITION;
                case PROVISIONING -> Decision.NO_OP;
                case PENDING, ACTIVE, SUSPENDED -> Decision.ILLEGAL;
            };
        };
    }

    /** The status a successful TRANSITION ends in (provisioning actions may instead end in FAILED). */
    public static TenantStatus target(TenantAction action) {
        return switch (action) {
            case SUSPEND -> TenantStatus.SUSPENDED;
            case APPROVE, REACTIVATE, REPROVISION -> TenantStatus.ACTIVE;
        };
    }
}
