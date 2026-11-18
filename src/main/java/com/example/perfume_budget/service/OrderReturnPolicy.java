package com.example.perfume_budget.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * ORD-07: the optional return window for DELIVERED orders. {@code shopfi.orders.return-window-days} (env
 * {@code SHOPFI_ORDER_RETURN_WINDOW_DAYS}): empty or 0 (the default) = no window, a DELIVERED order can always be
 * returned; a positive number of days = the return must be recorded before {@code delivered_at + days}. A negative
 * value is a configuration error and stops the application from starting.
 */
@Component
public class OrderReturnPolicy {

    private final Integer windowDays;

    public OrderReturnPolicy(@Value("${shopfi.orders.return-window-days:}") String windowDays) {
        if (windowDays == null || windowDays.isBlank()) {
            this.windowDays = null;
            return;
        }
        int days;
        try {
            days = Integer.parseInt(windowDays.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("shopfi.orders.return-window-days must be a whole number of days, empty or 0 for no window");
        }
        if (days < 0) {
            throw new IllegalStateException("shopfi.orders.return-window-days must not be negative");
        }
        this.windowDays = days == 0 ? null : days;
    }

    /** The window in days, or null when there is none. */
    public Integer windowDays() {
        return windowDays;
    }

    /** The last instant a return may be recorded, or null when there is no window or the delivery time is unknown. */
    public LocalDateTime deadline(LocalDateTime deliveredAt) {
        return windowDays == null || deliveredAt == null ? null : deliveredAt.plusDays(windowDays);
    }

    /** True when the window is configured and {@code now} is after the deadline. */
    public boolean isExpired(LocalDateTime deliveredAt, LocalDateTime now) {
        LocalDateTime deadline = deadline(deliveredAt);
        return deadline != null && now.isAfter(deadline);
    }
}
