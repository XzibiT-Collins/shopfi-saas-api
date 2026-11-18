package com.example.perfume_budget.config;

import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.TenantRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EVT-01c rework 1: the ERROR lines that lost their stack trace in EVT-01c carry one again (class names and frames
 * through {@code SafeStackTrace}), still without any exception message (which can quote personal data).
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorLogStackTraceTest {

    private static final String PII = "Key (email)=(trace-leak@x.test) already exists";

    private static RuntimeException failure() {
        return new IllegalStateException("could not save: " + PII, new SQLException(PII, "23505"));
    }

    private static void assertTraceWithoutMessage(String logged, String header) {
        int at = logged.indexOf(header);
        assertThat(at).as("logged: " + header).isNotNegative();
        String entry = logged.substring(at);
        assertThat(entry).contains(IllegalStateException.class.getName())
                .contains("Caused by: " + SQLException.class.getName())
                .contains("\tat " + ErrorLogStackTraceTest.class.getName() + ".failure(");
        assertThat(logged).doesNotContain("trace-leak@x.test").doesNotContain("Key (email)").doesNotContain("could not save");
    }

    @Test
    void theGeneric500HandlerLogsTheTraceWithoutTheMessage(CapturedOutput output) {
        new GlobalExceptionHandler().handleGenericException(failure());
        assertTraceWithoutMessage(output.getAll(), "Unexpected error occurred: ");
    }

    @Test
    void theTransactionFailureHandlerLogsTheTraceWithoutTheMessage(CapturedOutput output) {
        new GlobalExceptionHandler().handleTransactionException(new TransactionSystemException("commit failed", failure()));
        assertTraceWithoutMessage(output.getAll(), "Transaction failed: ");
    }

    @Test
    void aFailedScheduledActionLogsTheTraceWithoutTheMessage(CapturedOutput output) {
        TenantRegistryService registry = mock(TenantRegistryService.class);
        Tenant t = new Tenant();
        t.setSlug("acme");
        t.setSchemaName("tenant_acme");
        when(registry.findByStatus(TenantStatus.ACTIVE)).thenReturn(List.of(t));
        new TenantRunner(registry).forEachActiveTenant(() -> {
            throw failure();
        });
        assertTraceWithoutMessage(output.getAll(), "Scheduled action failed for tenant acme: ");
    }
}
