package com.example.perfume_budget.platform.plan.events;

import java.util.Set;

/**
 * Published INSIDE the transaction of every plan write that changed something (create, update, matrix, default-flag
 * move). Consumers must listen with {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so a rolled-back write evicts
 * nothing and a committed one is visible to the next request (plans.md section 15.1 item 5): the PLAN-02 entitlement cache
 * ({@code plans:v1:entitlements:{planId}}). The public-list cache is evicted after commit directly by the writer.
 *
 * @param planIds the immutable ids of every plan whose fields or entitlements changed
 */
public record PlansChangedEvent(Set<Long> planIds) {
}
