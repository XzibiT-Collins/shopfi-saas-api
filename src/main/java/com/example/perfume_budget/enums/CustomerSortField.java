package com.example.perfume_budget.enums;

import com.example.perfume_budget.exception.BadRequestException;

import java.util.Locale;

/** API-RD-10: whitelist of the sort keys of GET /admin/metrics/customers (never user text in the query). */
public enum CustomerSortField {
    ORDERS("COUNT(o.id)"),
    TOTAL_SPENT("COALESCE(SUM(CASE WHEN o.status = com.example.perfume_budget.enums.PaymentStatus.COMPLETED "
            + "AND (o.deliveryStatus IS NULL OR o.deliveryStatus NOT IN (com.example.perfume_budget.enums.OrderProcessingStatus.CANCELLED, "
            + "com.example.perfume_budget.enums.OrderProcessingStatus.RETURNED)) THEN o.totalAmount.amount ELSE 0 END), 0)"),
    LAST_ORDER("MAX(o.createdAt)");

    private final String jpql;

    CustomerSortField(String jpql) {
        this.jpql = jpql;
    }

    public String jpql() {
        return jpql;
    }

    /** Accepts orders | totalSpent | lastOrder (any case, also ORDERS / TOTAL_SPENT / LAST_ORDER); null means ORDERS. */
    public static CustomerSortField parse(String value) {
        if (value == null || value.isBlank()) return ORDERS;
        return switch (value.trim().replace("_", "").toLowerCase(Locale.ROOT)) {
            case "orders" -> ORDERS;
            case "totalspent" -> TOTAL_SPENT;
            case "lastorder" -> LAST_ORDER;
            default -> throw new BadRequestException("sortBy must be one of: orders, totalSpent, lastOrder");
        };
    }
}
