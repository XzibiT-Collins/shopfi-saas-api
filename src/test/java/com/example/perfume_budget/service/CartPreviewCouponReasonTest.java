package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.checkout.CartPreviewRequest;
import com.example.perfume_budget.dto.checkout.CartPreviewResponse.CouponPreview;
import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.CartUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** API-06: the cart preview's coupon.reason for every rejection reason; the per-user limit has its own code. */
class CartPreviewCouponReasonTest {

    private final AuthUserUtil auth = mock(AuthUserUtil.class);
    private final CartUtil cartUtil = mock(CartUtil.class);
    private final CheckoutPricingService pricing = mock(CheckoutPricingService.class);
    private final CouponPreviewRateLimiter limiter = mock(CouponPreviewRateLimiter.class);
    private final CartPreviewService service = new CartPreviewService(auth, cartUtil, pricing,
            mock(DeliveryDetailRepository.class), limiter);

    private static final String SPECIFIC = "specific message for the reason";

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        User u = new User();
        u.setId(5L);
        when(auth.getCurrentUser()).thenReturn(u);
        Cart cart = new Cart();
        List<CartItem> items = new ArrayList<>();
        items.add(new CartItem());
        cart.setItems(items);
        when(cartUtil.findCurrentUserCart()).thenReturn(Optional.of(cart));
        BigDecimal hundred = new BigDecimal("100.00");
        CheckoutPricingService.Quote noCoupon = new CheckoutPricingService.Quote(hundred, BigDecimal.ZERO, null,
                BigDecimal.ZERO, hundred, new TaxCalculationResult(List.of(), new Money(BigDecimal.ZERO, CurrencyCode.GHS),
                new Money(hundred, CurrencyCode.GHS)));
        when(pricing.quote(any(Cart.class), isNull())).thenReturn(noCoupon);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private CouponPreview previewRejectedWith(Reason reason, String message) {
        when(pricing.quote(any(Cart.class), eq(" once"))).thenThrow(new CouponRejectedException(reason, message));
        return service.preview(new CartPreviewRequest(" once", null)).coupon();
    }

    @ParameterizedTest
    @EnumSource(value = Reason.class, names = {"NOT_FOUND", "INACTIVE", "NOT_STARTED", "EXPIRED", "USAGE_LIMIT"})
    void existenceRevealingReasonsStayCollapsedIntoCouponInvalid(Reason reason) {
        CouponPreview c = previewRejectedWith(reason, SPECIFIC);
        assertThat(c.valid()).isFalse();
        assertThat(c.reason()).isEqualTo("COUPON_INVALID");
        assertThat(c.message()).isEqualTo(CheckoutPricingService.INVALID_COUPON_CODE);
    }

    @Test
    void perUserLimitHasItsOwnCodeAndCheckoutsMessage() {
        CouponPreview c = previewRejectedWith(Reason.PER_USER_LIMIT, CouponPerUserLimit.MESSAGE);
        assertThat(c.valid()).isFalse();
        assertThat(c.code()).isEqualTo("ONCE");
        assertThat(c.reason()).isEqualTo("COUPON_PER_USER_LIMIT").isEqualTo(CouponPerUserLimit.CODE);
        assertThat(c.message()).isEqualTo(CouponPerUserLimit.MESSAGE);
    }

    @Test
    void minAmountAndSaleItemsKeepTheirCodes() {
        assertThat(previewRejectedWith(Reason.MIN_AMOUNT, SPECIFIC).reason()).isEqualTo("COUPON_MIN_AMOUNT");
        reset(pricing);
        setUp();
        assertThat(previewRejectedWith(Reason.WITH_SALE_ITEMS, SPECIFIC).reason()).isEqualTo("COUPON_NOT_COMBINABLE");
    }

    @Test
    void everyReasonIsMapped_noneFallsThroughSilently() {
        // a new Reason must be classified on purpose: only these three escape COUPON_INVALID
        for (Reason r : Reason.values()) {
            reset(pricing);
            setUp();
            String code = previewRejectedWith(r, SPECIFIC).reason();
            boolean distinct = r == Reason.PER_USER_LIMIT || r == Reason.MIN_AMOUNT || r == Reason.WITH_SALE_ITEMS;
            assertThat(code.equals("COUPON_INVALID")).as(r.name()).isNotEqualTo(distinct);
        }
        verify(limiter, atLeastOnce()).check("acme", 5L);
    }
}
