package com.example.perfume_budget.projection;

/** INV-01b: one row of the legacy-balance detection query (native SQL aliases = these getters). */
public interface LegacyBalanceCandidate {
    Long getProductId();
    String getSku();
    String getName();
    Integer getStockQuantity();
    Integer getReservedQuantity();
    Integer getLocationOnHand();
}
