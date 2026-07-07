package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.ShopWideDiscountRepository;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Single source of truth for the price a customer actually pays.
 * Resolves a product's base price against any active product-specific discount
 * (which wins) or, failing that, the active shop-wide discount.
 */
@Service
@RequiredArgsConstructor
public class EffectivePriceService {

    private final ShopWideDiscountRepository shopWideDiscountRepository;
    private final Clock systemClock;

    public LocalDateTime now() {
        return LocalDateTime.now(systemClock);
    }

    /** The shop-wide discount currently in effect, if any. */
    public Optional<ShopWideDiscount> activeShopDiscount() {
        return shopWideDiscountRepository.findActiveNow(now());
    }

    /** Convenience overload that loads the active shop discount itself (one query). */
    public EffectivePrice compute(Product product) {
        return compute(product, activeShopDiscount().orElse(null), now());
    }

    /**
     * Core resolution. Pass the active shop discount and "now" explicitly so that
     * list endpoints can resolve them once and reuse across many products.
     */
    public EffectivePrice compute(Product product, ShopWideDiscount activeShop, LocalDateTime now) {
        Money price = product.getPrice();
        BigDecimal original = price != null && price.getAmount() != null
                ? price.getAmount()
                : BigDecimal.ZERO;
        var currency = price != null ? price.getCurrencyCode() : null;

        // 1. Product-specific discount wins.
        if (isProductDiscountActive(product, now)) {
            return build(original, currency,
                    DiscountCalculationUtil.computeDiscountAmount(product.getDiscountType(), product.getDiscountValue(), original),
                    DiscountSource.PRODUCT, product.getDiscountType(), product.getDiscountEndAt());
        }

        // 2. Shop-wide discount (percentage only).
        if (activeShop != null && activeShop.getDiscountPercentage() != null) {
            return build(original, currency,
                    DiscountCalculationUtil.computeDiscountAmount(DiscountType.PERCENTAGE, activeShop.getDiscountPercentage(), original),
                    DiscountSource.SHOP, DiscountType.PERCENTAGE, activeShop.getEndAt());
        }

        // 3. No discount.
        return new EffectivePrice(scale(original), scale(original), currency,
                false, DiscountSource.NONE, null, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN), null);
    }

    private EffectivePrice build(BigDecimal original, com.example.perfume_budget.enums.CurrencyCode currency,
                                 BigDecimal discountAmount, DiscountSource source,
                                 DiscountType type, LocalDateTime endsAt) {
        BigDecimal effective = scale(original.subtract(discountAmount).max(BigDecimal.ZERO));
        BigDecimal scaledOriginal = scale(original);
        boolean onSale = effective.compareTo(scaledOriginal) < 0;
        BigDecimal percentageOff = onSale && scaledOriginal.compareTo(BigDecimal.ZERO) > 0
                ? scaledOriginal.subtract(effective)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(scaledOriginal, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        return new EffectivePrice(scaledOriginal, effective, currency,
                onSale, onSale ? source : DiscountSource.NONE, onSale ? type : null,
                percentageOff, onSale ? endsAt : null);
    }

    private boolean isProductDiscountActive(Product product, LocalDateTime now) {
        return product.getDiscountType() != null
                && product.getDiscountValue() != null
                && product.getDiscountStartAt() != null
                && product.getDiscountEndAt() != null
                && !now.isBefore(product.getDiscountStartAt())
                && !now.isAfter(product.getDiscountEndAt());
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }
}
