package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.ShopWideDiscountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectivePriceServiceTest {

    @Mock
    private ShopWideDiscountRepository shopWideDiscountRepository;

    private EffectivePriceService service;
    private final LocalDateTime now = LocalDateTime.of(2026, 5, 30, 12, 0);

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new EffectivePriceService(shopWideDiscountRepository, fixedClock);
    }

    private Product productPriced(String amount) {
        return Product.builder()
                .id(1L)
                .name("P")
                .price(new Money(new BigDecimal(amount), CurrencyCode.GHS))
                .build();
    }

    private void withProductDiscount(Product p, DiscountType type, String value, LocalDateTime start, LocalDateTime end) {
        p.setDiscountType(type);
        p.setDiscountValue(new BigDecimal(value));
        p.setDiscountStartAt(start);
        p.setDiscountEndAt(end);
    }

    private ShopWideDiscount shop(String pct) {
        return ShopWideDiscount.builder()
                .discountPercentage(new BigDecimal(pct))
                .startAt(now.minusDays(1))
                .endAt(now.plusDays(1))
                .isActive(true)
                .build();
    }

    @Test
    void productPercentageDiscount_appliesAndDerivesPercentage() {
        Product p = productPriced("100.00");
        withProductDiscount(p, DiscountType.PERCENTAGE, "20", now.minusHours(1), now.plusHours(1));

        EffectivePrice result = service.compute(p, null, now);

        assertTrue(result.onSale());
        assertEquals(DiscountSource.PRODUCT, result.source());
        assertEquals(0, new BigDecimal("80.00").compareTo(result.effectiveAmount()));
        assertEquals(0, new BigDecimal("20.00").compareTo(result.discountPercentage()));
    }

    @Test
    void productFlatDiscount_appliesAndDerivesPercentage() {
        Product p = productPriced("100.00");
        withProductDiscount(p, DiscountType.FLAT, "30", now.minusHours(1), now.plusHours(1));

        EffectivePrice result = service.compute(p, null, now);

        assertTrue(result.onSale());
        assertEquals(0, new BigDecimal("70.00").compareTo(result.effectiveAmount()));
        assertEquals(0, new BigDecimal("30.00").compareTo(result.discountPercentage()));
    }

    @Test
    void shopWideDiscount_appliesWhenNoProductDiscount() {
        Product p = productPriced("100.00");

        EffectivePrice result = service.compute(p, shop("10"), now);

        assertTrue(result.onSale());
        assertEquals(DiscountSource.SHOP, result.source());
        assertEquals(0, new BigDecimal("90.00").compareTo(result.effectiveAmount()));
    }

    @Test
    void productDiscountWinsOverShopWide() {
        Product p = productPriced("100.00");
        withProductDiscount(p, DiscountType.PERCENTAGE, "20", now.minusHours(1), now.plusHours(1));

        EffectivePrice result = service.compute(p, shop("50"), now);

        assertEquals(DiscountSource.PRODUCT, result.source());
        assertEquals(0, new BigDecimal("80.00").compareTo(result.effectiveAmount()));
    }

    @Test
    void expiredProductDiscount_revertsToOriginal() {
        Product p = productPriced("100.00");
        withProductDiscount(p, DiscountType.PERCENTAGE, "20", now.minusDays(2), now.minusDays(1));

        EffectivePrice result = service.compute(p, null, now);

        assertFalse(result.onSale());
        assertEquals(DiscountSource.NONE, result.source());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.effectiveAmount()));
    }

    @Test
    void futureProductDiscount_notYetApplied() {
        Product p = productPriced("100.00");
        withProductDiscount(p, DiscountType.PERCENTAGE, "20", now.plusDays(1), now.plusDays(2));

        EffectivePrice result = service.compute(p, null, now);

        assertFalse(result.onSale());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.effectiveAmount()));
    }

    @Test
    void flatDiscountExceedingPrice_clampsToZero() {
        Product p = productPriced("100.00");
        withProductDiscount(p, DiscountType.FLAT, "150", now.minusHours(1), now.plusHours(1));

        EffectivePrice result = service.compute(p, null, now);

        assertTrue(result.onSale());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.effectiveAmount()));
    }

    @Test
    void noDiscount_returnsOriginal() {
        Product p = productPriced("100.00");

        EffectivePrice result = service.compute(p, null, now);

        assertFalse(result.onSale());
        assertEquals(DiscountSource.NONE, result.source());
        assertNull(result.discountType());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.effectiveAmount()));
    }

    @Test
    void activeShopDiscount_delegatesToRepository() {
        ShopWideDiscount active = shop("15");
        when(shopWideDiscountRepository.findActiveNow(now)).thenReturn(java.util.Optional.of(active));

        assertTrue(service.activeShopDiscount().isPresent());
    }
}
