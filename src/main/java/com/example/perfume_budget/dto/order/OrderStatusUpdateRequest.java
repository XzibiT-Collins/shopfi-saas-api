package com.example.perfume_budget.dto.order;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import jakarta.validation.constraints.NotNull;

/**
 * @param orderStatus target delivery status
 * @param version     optional optimistic-concurrency token: the {@code version} of the order the admin last saw (the
 *                    {@code If-Match} header is the alternative). A stale value is a 409; omitted = no check (deprecated).
 */
public record OrderStatusUpdateRequest(
        @NotNull OrderProcessingStatus orderStatus,
        Long version
) {
    public OrderStatusUpdateRequest(OrderProcessingStatus orderStatus) {
        this(orderStatus, null);
    }
}
