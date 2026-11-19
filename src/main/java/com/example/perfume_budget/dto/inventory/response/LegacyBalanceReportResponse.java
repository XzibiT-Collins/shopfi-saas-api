package com.example.perfume_budget.dto.inventory.response;

import java.util.List;

/**
 * INV-01b L3: products whose legacy {@code stock_quantity} was overwritten by a pre-INV-01 receipt. Read only.
 * {@code lostUnits = locationOnHand - stockQuantity - reservedQuantity}; {@code repairable} when that is positive.
 */
public record LegacyBalanceReportResponse(List<Line> products, int totalLostUnits, boolean truncated) {

    public record Line(Long productId, String sku, String name, int stockQuantity, int reservedQuantity,
                       int locationOnHand, int lostUnits, boolean repairable) {
    }
}
