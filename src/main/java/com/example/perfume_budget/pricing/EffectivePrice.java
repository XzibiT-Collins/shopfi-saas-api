package com.example.perfume_budget.pricing;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.model.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Result of resolving a product's price against any active product-specific or shop-wide discount.
 * Single source of truth so display and checkout never diverge.
 */
public record EffectivePrice(
        BigDecimal originalAmount,
        BigDecimal effectiveAmount,
        CurrencyCode currencyCode,
        boolean onSale,
        DiscountSource source,
        DiscountType discountType,
        BigDecimal discountPercentage, // derived for display (0 when not on sale)
        LocalDateTime endsAt
) {
    public Money originalMoney() {
        return new Money(originalAmount, currencyCode);
    }

    public Money effectiveMoney() {
        return new Money(effectiveAmount, currencyCode);
    }

    /** "GHS 49.99" style, matching Money.toString(); falls back to the bare amount when currency is unknown. */
    public String originalDisplay() {
        return currencyCode == null ? originalAmount.toPlainString() : originalMoney().toString();
    }

    public String effectiveDisplay() {
        return currencyCode == null ? effectiveAmount.toPlainString() : effectiveMoney().toString();
    }
}
