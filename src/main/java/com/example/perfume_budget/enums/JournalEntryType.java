package com.example.perfume_budget.enums;

public enum JournalEntryType {
    SALE,
    REFUND,
    DISCOUNT,
    TAX_COLLECTION,
    INVENTORY,
    WALK_IN_SALE,
    INVENTORY_PURCHASE,
    INVENTORY_DECREASE,
    INVENTORY_INCREASE,
    ADJUSTMENT,
    INVENTORY_REVALUATION,
    /** ORD-02: reversal of an online SALE after a paid admin cancel (revenue part and/or returned-goods COGS part). */
    SALE_REVERSAL
}
