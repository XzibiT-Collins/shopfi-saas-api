package com.example.perfume_budget.enums;

public enum InventoryAllocationStatus {
    RESERVED,
    CONSUMED,
    RELEASED,
    /** Consumed stock handed back after a paid order was cancelled before dispatch (ORD-01). */
    RETURNED
}
