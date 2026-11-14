package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.service.interfaces.TaxService;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The ONE place that turns a cart (+ optional coupon) into money. Checkout and the cart preview both call it, so the
 * preview can never drift from what is charged. It has no side effects: the coupon usage counter is advanced by the
 * caller only when an order is really created.
 */
@Service
@RequiredArgsConstructor
public class CheckoutPricingService {

    public static final String INVALID_COUPON_CODE = "Invalid coupon code.";
    public static final String COUPON_WITH_DISCOUNT = "Coupons cannot be combined with active product discounts.";

    private final EffectivePriceService effectivePriceService;
    private final CouponRepository couponRepository;
    private final DiscountCalculationUtil discountCalculationUtil;
    private final TaxService taxService;

    public record Quote(
            BigDecimal orderSubtotal,        // net of automatic discounts
            BigDecimal automaticDiscount,
            Coupon coupon,                   // null when no coupon
            BigDecimal couponDiscount,       // ZERO when no coupon
            BigDecimal discountedSubtotal,   // taxable base
            TaxCalculationResult taxes
    ) {
        public BigDecimal originalSubtotal() {
            return orderSubtotal.add(automaticDiscount);
        }
    }

    /**
     * @param couponCode raw client value; null/blank means "no coupon"
     * @throws CouponRejectedException (a BadRequestException) when the coupon cannot be applied
     */
    public Quote quote(Cart cart, String couponCode) {
        ShopWideDiscount activeShop = effectivePriceService.activeShopDiscount().orElse(null);
        LocalDateTime pricingTime = effectivePriceService.now();

        BigDecimal orderSubtotal = BigDecimal.ZERO;
        BigDecimal automaticDiscount = BigDecimal.ZERO;
        boolean anyItemOnSale = false;
        for (CartItem cartItem : cart.getItems()) {
            EffectivePrice price = effectivePriceService.compute(cartItem.getProduct(), activeShop, pricingTime);
            if (price.onSale()) {
                anyItemOnSale = true;
            }
            BigDecimal quantity = BigDecimal.valueOf(cartItem.getQuantity());
            orderSubtotal = orderSubtotal.add(price.effectiveAmount().multiply(quantity));
            automaticDiscount = automaticDiscount.add(
                    price.originalAmount().subtract(price.effectiveAmount()).multiply(quantity));
        }

        Coupon coupon = null;
        BigDecimal couponDiscount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            if (anyItemOnSale) {
                throw new CouponRejectedException(Reason.WITH_SALE_ITEMS, COUPON_WITH_DISCOUNT);
            }
            coupon = couponRepository.findByCode(couponCode.strip().toUpperCase())
                    .orElseThrow(() -> new CouponRejectedException(Reason.NOT_FOUND, INVALID_COUPON_CODE));
            discountCalculationUtil.checkIfCouponIsValid(coupon);
            couponDiscount = discountCalculationUtil.calculateDiscount(coupon, orderSubtotal);
        }
        BigDecimal discounted = orderSubtotal.subtract(couponDiscount).max(BigDecimal.ZERO);
        // The discount actually applied is bounded by the subtotal (the amount can never go negative).
        couponDiscount = orderSubtotal.subtract(discounted);

        TaxCalculationResult taxes = taxService.calculateTaxes(discounted);
        return new Quote(orderSubtotal, automaticDiscount, coupon, couponDiscount, discounted, taxes);
    }
}
