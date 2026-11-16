package com.example.perfume_budget.dto.discount.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A shop-wide discount as the admin sees it. {@code isActive} is the on/off switch; {@code currentlyActive} is true only
 * while it is switched on AND inside its window. {@code status} (API-06) says which case applies.
 */
@Builder
public record ShopWideDiscountResponse(
        Long id,
        String label,
        BigDecimal discountPercentage,
        LocalDateTime startAt,
        LocalDateTime endAt,
        boolean isActive,
        boolean currentlyActive,
        Status status
) {
    public enum Status {
        /** switched on, start still in the future */
        SCHEDULED,
        /** switched on and inside its window: prices use it */
        ACTIVE,
        /** switched on, window over */
        EXPIRED,
        /** switched off (ended by the admin or replaced by a newer discount) */
        INACTIVE
    }
}
