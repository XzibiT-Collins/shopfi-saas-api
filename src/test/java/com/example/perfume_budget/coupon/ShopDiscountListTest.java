package com.example.perfume_budget.coupon;

import com.example.perfume_budget.dto.discount.response.ShopWideDiscountResponse;
import com.example.perfume_budget.dto.discount.response.ShopWideDiscountResponse.Status;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.repository.ShopWideDiscountRepository;
import com.example.perfume_budget.service.ShopWideDiscountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** API-06: the admin list of discounts not over yet (in force or scheduled) and the status of each response. */
class ShopDiscountListTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 12, 0);
    private final ShopWideDiscountRepository repo = mock(ShopWideDiscountRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneId.of("UTC"));
    private final ShopWideDiscountServiceImpl service = new ShopWideDiscountServiceImpl(repo, clock);

    private static ShopWideDiscount row(long id, String start, String end, boolean on) {
        return ShopWideDiscount.builder().id(id).label("Sale " + id).discountPercentage(new BigDecimal("10"))
                .startAt(LocalDateTime.parse(start)).endAt(LocalDateTime.parse(end)).isActive(on).build();
    }

    @Test
    void listsWhatTheRepositoryReturnsForClockNow_scheduledIncluded_withStatus() {
        when(repo.findNotEnded(eq(NOW), any(Pageable.class))).thenReturn(List.of(
                row(1, "2026-06-01T00:00:00", "2026-06-30T00:00:00", true),
                row(2, "2026-07-01T00:00:00", "2026-07-10T00:00:00", true)));
        List<ShopWideDiscountResponse> out = service.listNotEnded();
        assertThat(out).extracting(ShopWideDiscountResponse::id).containsExactly(1L, 2L);
        assertThat(out.get(0).status()).isEqualTo(Status.ACTIVE);
        assertThat(out.get(0).currentlyActive()).isTrue();
        assertThat(out.get(1).status()).isEqualTo(Status.SCHEDULED);
        assertThat(out.get(1).currentlyActive()).isFalse();
        assertThat(out.get(1).isActive()).isTrue();
        verify(repo, never()).findActiveNow(any());
    }

    @Test
    void listIsBoundedToFiftyRowsFromTheFirstPage() {
        when(repo.findNotEnded(any(), any())).thenReturn(List.of());
        assertThat(service.listNotEnded()).isEmpty();
        verify(repo).findNotEnded(eq(NOW), eq(org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    @Test
    void statusCoversEveryCase_boundariesInclusive() {
        // window edges: start == now and end == now are both ACTIVE (same rule as findActive / EffectivePriceService)
        assertThat(statusOf(row(1, "2026-06-15T12:00:00", "2026-06-20T00:00:00", true))).isEqualTo(Status.ACTIVE);
        assertThat(statusOf(row(1, "2026-06-01T00:00:00", "2026-06-15T12:00:00", true))).isEqualTo(Status.ACTIVE);
        assertThat(statusOf(row(1, "2026-06-15T12:00:01", "2026-06-20T00:00:00", true))).isEqualTo(Status.SCHEDULED);
        assertThat(statusOf(row(1, "2026-06-01T00:00:00", "2026-06-15T11:59:59", true))).isEqualTo(Status.EXPIRED);
        assertThat(statusOf(row(1, "2026-06-01T00:00:00", "2026-06-30T00:00:00", false))).isEqualTo(Status.INACTIVE);
        assertThat(statusOf(row(1, "2026-07-01T00:00:00", "2026-07-30T00:00:00", false))).isEqualTo(Status.INACTIVE);
    }

    private Status statusOf(ShopWideDiscount d) {
        when(repo.findActiveNow(any())).thenReturn(Optional.of(d));
        return service.getCurrent().status();
    }
}
