package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.OrderProcessingStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.example.perfume_budget.enums.OrderProcessingStatus.*;

/**
 * The delivery-status graph an admin may walk (ORD-01, docs/api/orders.md). Forward only, skipping ahead allowed,
 * CANCELLED from any non-final state, DELIVERED, CANCELLED and RETURNED are final for a status change. A DELIVERED
 * order can still be returned with its own route (ORD-07), which is the only way to RETURNED.
 *
 * <pre>
 * PENDING -> PACKING -> OUT_FOR_DELIVERY -> DELIVERED
 *    |          |              |
 *    +----------+--------------+--> CANCELLED
 * </pre>
 */
public final class OrderTransitionRules {

    private static final Map<OrderProcessingStatus, Set<OrderProcessingStatus>> ALLOWED = new EnumMap<>(OrderProcessingStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(PACKING, OUT_FOR_DELIVERY, DELIVERED, CANCELLED));
        ALLOWED.put(PACKING, EnumSet.of(OUT_FOR_DELIVERY, DELIVERED, CANCELLED));
        ALLOWED.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, CANCELLED));
        ALLOWED.put(DELIVERED, EnumSet.noneOf(OrderProcessingStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderProcessingStatus.class));
        // ORD-07: RETURNED is reached only through POST /order/{n}/return (never through a status change) and is final
        ALLOWED.put(RETURNED, EnumSet.noneOf(OrderProcessingStatus.class));
    }

    private OrderTransitionRules() {
    }

    public static boolean isFinal(OrderProcessingStatus status) {
        return status == DELIVERED || status == CANCELLED || status == RETURNED;
    }

    /** True when {@code from -> to} is a real change the graph allows (same status is handled by the caller as a no-op). */
    public static boolean isAllowed(OrderProcessingStatus from, OrderProcessingStatus to) {
        return from != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<OrderProcessingStatus> next(OrderProcessingStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }
}
