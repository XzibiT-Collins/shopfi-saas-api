package com.example.perfume_budget.task;

import com.example.perfume_budget.service.ReservedProductCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservedProductCleanupTask {
    private final ReservedProductCleanupService reservedProductCleanupService;
    private final Clock systemClock;

    @Value("${inventory.reservation.expiry-hours:1}")
    private long expiryHours;

    @Scheduled(fixedDelayString = "${inventory.reservation.cleanup-interval-ms:900000}")
    public void cleanupExpiredReservations() {
        log.info("Running reserved product cleanup task");
        LocalDateTime cutoff = LocalDateTime.now(systemClock).minusHours(expiryHours);
        int cleanedOrders = reservedProductCleanupService.cleanupExpiredReservations(cutoff);
        if (cleanedOrders > 0) {
            log.info("Reserved product cleanup released {} expired order(s)", cleanedOrders);
        } else {
            log.debug("Reserved product cleanup found no expired reservations");
        }
    }
}
