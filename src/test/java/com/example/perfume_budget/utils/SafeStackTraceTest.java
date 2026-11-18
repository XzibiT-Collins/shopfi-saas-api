package com.example.perfume_budget.utils;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** EVT-01c rework 1: stack traces at ERROR again, but with class names and frames only, never a message. */
class SafeStackTraceTest {

    private static final String PII = "Key (email)=(leak@x.test) already exists";

    private static RuntimeException failure() {
        SQLException root = new SQLException(PII, "23505");
        IllegalStateException wrapped = new IllegalStateException("could not save " + PII, root);
        wrapped.addSuppressed(new IllegalArgumentException("suppressed " + PII));
        return new RuntimeException("outer " + PII, wrapped);
    }

    @Test
    void theTraceHasEveryClassOfTheChainAndItsFrames_butNoMessage() {
        String trace = SafeStackTrace.of(failure()).toString();
        assertThat(trace).startsWith(RuntimeException.class.getName() + "\n");
        assertThat(trace).contains("Caused by: " + IllegalStateException.class.getName() + "\n");
        // SEC-01d: the SQL exception's line carries its SQLState (safe), still no message
        assertThat(trace).contains("Caused by: " + SQLException.class.getName() + " [SQLState 23505]\n");
        assertThat(trace).contains("Suppressed: " + IllegalArgumentException.class.getName() + "\n");
        assertThat(trace).contains("\tat " + SafeStackTraceTest.class.getName() + ".failure(SafeStackTraceTest.java:");
        assertThat(trace).contains("\tat " + SafeStackTraceTest.class.getName()
                + ".theTraceHasEveryClassOfTheChainAndItsFrames_butNoMessage(SafeStackTraceTest.java:");
        assertThat(trace).doesNotContain("leak@x.test").doesNotContain("Key (email)").doesNotContain("could not save");
    }

    @Test
    void framesInCommonWithTheEnclosingTraceAreElided() {
        String trace = SafeStackTrace.of(failure()).toString();
        assertThat(trace).containsPattern("\\t\\.\\.\\. \\d+ more\\n");
    }

    @Test
    void aCircularCauseChainTerminates() {
        IllegalStateException a = new IllegalStateException("a " + PII);
        IllegalArgumentException b = new IllegalArgumentException("b " + PII, a);
        a.initCause(b);
        String trace = SafeStackTrace.of(a).toString();
        assertThat(trace).contains("[CIRCULAR REFERENCE: " + IllegalStateException.class.getName() + "]");
        assertThat(trace).doesNotContain("leak@x.test");
    }

    @Test
    void aVeryLongTraceIsCut() {
        RuntimeException e = new RuntimeException(PII);
        StackTraceElement f = new StackTraceElement("x.Y", "m", "Y.java", 1);
        StackTraceElement[] frames = new StackTraceElement[500];
        java.util.Arrays.fill(frames, f);
        e.setStackTrace(frames);
        String trace = SafeStackTrace.of(e).toString();
        assertThat(trace.lines().filter(l -> l.startsWith("\tat ")).count()).isEqualTo(SafeStackTrace.MAX_FRAMES);
        assertThat(trace).contains("more frames omitted");
    }

    @Test
    void nullIsRendered() {
        assertThat(SafeStackTrace.of(null)).hasToString("null");
    }
}
