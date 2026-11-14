package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.checkout.CartPreviewRequest;
import com.example.perfume_budget.dto.checkout.CartPreviewResponse;
import com.example.perfume_budget.dto.checkout.CartPreviewResponse.CouponPreview;
import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.mapper.OrderTaxMapper;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.CartUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Cart totals preview (and coupon validation). Read only: never creates an order, never reserves stock and never
 * advances a coupon's usage counter. Uses {@link CheckoutPricingService}, the same code path as checkout, so what is
 * shown is what is charged. A coupon that cannot be applied does not fail the call: the totals are returned without it
 * and {@code coupon.valid=false} with a reason code. The reasons for "no such coupon / inactive / not started / expired
 * / used up" are deliberately collapsed into one {@code COUPON_INVALID} so the endpoint does not reveal which codes exist.
 */
@Service
@RequiredArgsConstructor
public class CartPreviewService {

    static final String EMPTY_CART = "Cart is empty, add items and try again.";

    private final AuthUserUtil authUserUtil;
    private final CartUtil cartUtil;
    private final CheckoutPricingService pricingService;
    private final DeliveryDetailRepository deliveryDetailRepository;
    private final CouponPreviewRateLimiter rateLimiter;

    @Transactional(readOnly = true)
    public CartPreviewResponse preview(CartPreviewRequest request) {
        User user = authUserUtil.getCurrentUser();
        String couponCode = request == null ? null : request.couponCode();
        boolean hasCoupon = couponCode != null && !couponCode.isBlank();
        if (hasCoupon) {
            rateLimiter.check(TenantContext.getSlug(), user.getId());
        }
        if (request != null && request.deliveryDetailId() != null) {
            deliveryDetailRepository.findByIdAndUser(request.deliveryDetailId(), user)
                    .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "DELIVERY_DETAIL_NOT_FOUND",
                            "Delivery address not found."));
        }
        Cart cart = cartUtil.getCurrentUserCart();
        if (cart.getItems().isEmpty()) {
            throw new BadRequestException(EMPTY_CART);
        }

        CheckoutPricingService.Quote quote;
        CouponPreview couponPreview = null;
        try {
            quote = pricingService.quote(cart, couponCode);
            if (hasCoupon) {
                couponPreview = new CouponPreview(couponCode.strip().toUpperCase(), true, null, null);
            }
        } catch (CouponRejectedException e) {
            quote = pricingService.quote(cart, null);
            couponPreview = rejected(couponCode, e);
        }

        CurrencyCode ccy = CurrencyCode.GHS;
        return CartPreviewResponse.builder()
                .subtotal(money(quote.orderSubtotal(), ccy))
                .originalSubtotal(money(quote.originalSubtotal(), ccy))
                .automaticDiscountAmount(money(quote.automaticDiscount(), ccy))
                .couponDiscountAmount(money(quote.couponDiscount(), ccy))
                .discountedSubtotal(money(quote.discountedSubtotal(), ccy))
                .taxes(TaxCalculationResponse.builder()
                        .orderTaxes(quote.taxes().orderTaxes().stream().map(OrderTaxMapper::toOrderTaxResponse).toList())
                        .totalTaxAmount(quote.taxes().totalTaxAmount().toString())
                        .totalAmountAfterTax(quote.taxes().totalAmountAfterTax().toString())
                        .build())
                .deliveryFee(money(BigDecimal.ZERO, ccy))
                .totalAmount(quote.taxes().totalAmountAfterTax().toString())
                .coupon(couponPreview)
                .build();
    }

    private static CouponPreview rejected(String couponCode, CouponRejectedException e) {
        String code = couponCode.strip().toUpperCase();
        return switch (e.getReason()) {
            case MIN_AMOUNT -> new CouponPreview(code, false, "COUPON_MIN_AMOUNT", e.getMessage());
            case WITH_SALE_ITEMS -> new CouponPreview(code, false, "COUPON_NOT_COMBINABLE", e.getMessage());
            default -> new CouponPreview(code, false, "COUPON_INVALID", CheckoutPricingService.INVALID_COUPON_CODE);
        };
    }

    private static String money(BigDecimal amount, CurrencyCode ccy) {
        return new Money(amount, ccy).toString();
    }
}
