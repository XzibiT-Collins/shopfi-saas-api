package com.example.perfume_budget.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * EVT-01d (4): a failed drain round logs its ERROR line WITH the stack trace (class names and frames through
 * SafeStackTrace), never the exception message. Reviewer mutant W (back to the class name only) survived without this.
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxDrainRoundLogTest {

    private static final String PII = "Key (email)=(drain-leak@x.test) already exists";
    private static final String HEADER = "Outbox drain round failed; the next round runs as scheduled: ";

    private static RuntimeException failure() {
        return new IllegalStateException("drain failed: " + PII, new SQLException(PII, "23505"));
    }

    @Test
    void aFailedRoundLogsTheTraceWithoutTheMessage_andTheNextRoundStillRuns(CapturedOutput output) {
        OutboxDrainer drainer = mock(OutboxDrainer.class);
        doThrow(failure()).when(drainer).drainAllTenants();
        OutboxDrainScheduler scheduler = new OutboxDrainScheduler(drainer, 10, 0);

        scheduler.round(); // must not throw: the next round still runs

        String all = output.getAll();
        int at = all.indexOf(HEADER);
        assertThat(at).as("the drain-round ERROR line").isNotNegative();
        String entry = all.substring(at);
        assertThat(all.substring(Math.max(0, all.lastIndexOf('\n', at)), at)).contains("ERROR");
        assertThat(entry).startsWith(HEADER + IllegalStateException.class.getName() + "\n")
                .contains("Caused by: " + SQLException.class.getName())
                .contains("\tat " + OutboxDrainRoundLogTest.class.getName() + ".failure(OutboxDrainRoundLogTest.java:");
        assertThat(all).doesNotContain("drain-leak@x.test").doesNotContain("Key (email)").doesNotContain("drain failed");
    }

    @Test
    void anErrorInTheRoundIsLoggedWithItsTraceToo(CapturedOutput output) {
        OutboxDrainer drainer = mock(OutboxDrainer.class);
        doThrow(new AssertionError("boom " + PII)).when(drainer).drainAllTenants();
        new OutboxDrainScheduler(drainer, 10, 0).round();
        String all = output.getAll();
        assertThat(all).contains(HEADER + AssertionError.class.getName() + "\n");
        assertThat(all.substring(all.indexOf(HEADER))).contains("\tat ");
        assertThat(all).doesNotContain("drain-leak@x.test");
    }
}
