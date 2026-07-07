package com.example.perfume_budget.dto.product.response;

import com.example.perfume_budget.enums.ConversionDirection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockConversionResponse(
        Long id,
        String conversionNumber,
        ConversionDirection direction,
        Long fromProductId,
        String fromProductName,
        Integer fromQuantity,
        Long toProductId,
        String toProductName,
        Integer toQuantity,
        BigDecimal fromCostValue,
        BigDecimal toCostValue,
        BigDecimal varianceAmount,
        String convertedBy,
        String notes,
        LocalDateTime convertedAt
) {
}
