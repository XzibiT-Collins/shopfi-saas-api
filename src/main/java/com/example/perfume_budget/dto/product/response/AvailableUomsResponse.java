package com.example.perfume_budget.dto.product.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record AvailableUomsResponse(
        String familyCode,
        BigDecimal baseUnitCost,
        List<UnitOfMeasureResponse> availableUoms,
        Set<String> takenUoms
) {
}
