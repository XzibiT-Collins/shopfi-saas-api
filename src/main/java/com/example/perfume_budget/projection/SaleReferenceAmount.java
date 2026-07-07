package com.example.perfume_budget.projection;

import com.example.perfume_budget.enums.InventoryReferenceType;

import java.math.BigDecimal;

/** Total sold amount for one product grouped by its originating reference (order). */
public interface SaleReferenceAmount {
    InventoryReferenceType getReferenceType();
    String getReferenceId();
    BigDecimal getAmount();
}
