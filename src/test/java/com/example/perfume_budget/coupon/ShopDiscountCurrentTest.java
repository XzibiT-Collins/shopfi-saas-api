package com.example.perfume_budget.coupon;

import com.example.perfume_budget.dto.discount.request.ShopWideDiscountRequest;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.repository.ShopWideDiscountRepository;
import com.example.perfume_budget.service.ShopWideDiscountServiceImpl;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** API-04: GET shop-discount/current returns only a discount in force now; label is bounded. */
class ShopDiscountCurrentTest {

    private final ShopWideDiscountRepository repo = mock(ShopWideDiscountRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneId.of("UTC"));
    private final ShopWideDiscountServiceImpl service = new ShopWideDiscountServiceImpl(repo, clock);

    private static ShopWideDiscount row(String start, String end) {
        return ShopWideDiscount.builder().id(7L).label("Sale").discountPercentage(new BigDecimal("10"))
                .startAt(LocalDateTime.parse(start)).endAt(LocalDateTime.parse(end)).isActive(true).build();
    }

    @Test
    void asksTheRepositoryForTheDiscountInForceAtClockNow() {
        when(repo.findActiveNow(LocalDateTime.of(2026, 6, 15, 12, 0)))
                .thenReturn(Optional.of(row("2026-06-01T00:00:00", "2026-06-30T00:00:00")));
        var r = service.getCurrent();
        assertThat(r.id()).isEqualTo(7L);
        assertThat(r.currentlyActive()).isTrue();
        verify(repo, never()).findByIsActiveTrueOrderByCreatedAtDesc();
    }

    @Test
    void expiredOrNotYetStartedRowIsNotReturned() {
        when(repo.findActiveNow(any())).thenReturn(Optional.empty());
        assertThatThrownBy(service::getCurrent).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void labelLongerThan100Rejected_100Accepted() {
        var v = Validation.buildDefaultValidatorFactory().getValidator();
        var ok = new ShopWideDiscountRequest("x".repeat(100), BigDecimal.TEN, LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        var bad = new ShopWideDiscountRequest("x".repeat(101), BigDecimal.TEN, LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        assertThat(v.validate(ok)).isEmpty();
        assertThat(v.validate(bad)).extracting(c -> c.getMessage()).containsExactly("Label cannot exceed 100 characters");
    }
}
