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
    /**
     * API-RD-12c: a manually recorded business expense (rent, utilities, ...). Stored as the same VARCHAR as the other
     * types, so no migration; the ledger effect comes from the lines, as for every manual entry.
     */
    EXPENSE,
    INVENTORY_REVALUATION,
    /** ORD-02: reversal of an online SALE after a paid admin cancel (revenue part and/or returned-goods COGS part). */
    SALE_REVERSAL,
    /**
     * ORD-02b: cash collected for an online order that was already closed (merchant cancel, or customer cancel / expiry
     * / failure without stock to revive it): debit Cash and Bank, credit Refunds Payable. No revenue is recognised; the
     * refund acknowledgement ({@link #REFUND}) clears both.
     */
    LATE_PAYMENT
}
