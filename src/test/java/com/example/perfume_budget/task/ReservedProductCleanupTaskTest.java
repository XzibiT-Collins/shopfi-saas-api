package com.example.perfume_budget.task;

import com.example.perfume_budget.service.ReservedProductCleanupService;
import com.example.perfume_budget.tenancy.TenantRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservedProductCleanupTaskTest {

    @Mock
    private ReservedProductCleanupService reservedProductCleanupService;

    @Mock
    private TenantRunner tenantRunner;

    @Test
    void cleanupExpiredReservations_DelegatesWithOneHourCutoff() {
        // Run the per-tenant action inline so the delegation can be asserted.
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(tenantRunner).forEachActiveTenant(any());

        Clock clock = Clock.fixed(Instant.parse("2026-05-11T12:00:00Z"), ZoneOffset.UTC);
        ReservedProductCleanupTask reservedProductCleanupTask =
                new ReservedProductCleanupTask(reservedProductCleanupService, tenantRunner, clock);
        ReflectionTestUtils.setField(reservedProductCleanupTask, "expiryHours", 1L);

        reservedProductCleanupTask.cleanupExpiredReservations();

        verify(reservedProductCleanupService).cleanupExpiredReservations(LocalDateTime.of(2026, 5, 11, 11, 0));
    }
}
