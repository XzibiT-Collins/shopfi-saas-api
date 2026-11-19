package com.example.perfume_budget.dto.inventory.response;

import java.util.List;

/** INV-01b L3: per requested product, in id order: {@code REPAIRED} (units restored) or {@code SKIPPED} with a reason. */
public record LegacyBalanceRepairResponse(List<Line> results, int restoredUnits) {

    public record Line(Long productId, String outcome, int restoredUnits, String reason) {
    }
}
