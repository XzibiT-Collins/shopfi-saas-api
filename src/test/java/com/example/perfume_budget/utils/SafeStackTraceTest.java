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

    // ---- EVT-01d (6): bounded fan-out

    /** 2,000 suppressed exceptions used to render as one ~363 KB log line. */
    @Test
    void twoThousandSuppressed_onlyTheFirstFewAreRendered_theRestCounted_andTheLineIsBounded() {
        RuntimeException top = new RuntimeException(PII);
        for (int i = 0; i < 2000; i++) {
            top.addSuppressed(new IllegalArgumentException("s" + i + " " + PII));
        }
        String trace = SafeStackTrace.of(top).toString();
        assertThat(trace.lines().filter(l -> l.startsWith("\tSuppressed: ")).count())
                .isEqualTo(SafeStackTrace.MAX_SUPPRESSED);
        assertThat(trace).contains("\t... " + (2000 - SafeStackTrace.MAX_SUPPRESSED) + " more suppressed omitted\n");
        assertThat(trace.length()).isLessThanOrEqualTo(SafeStackTrace.MAX_LENGTH + SafeStackTrace.TRUNCATED.length() + 1);
        assertThat(trace).doesNotContain("leak@x.test");
    }

    @Test
    void fewSuppressed_areAllRendered_withoutAnOmittedLine() {
        RuntimeException top = new RuntimeException(PII);
        for (int i = 0; i < SafeStackTrace.MAX_SUPPRESSED; i++) {
            top.addSuppressed(new IllegalArgumentException(PII));
        }
        String trace = SafeStackTrace.of(top).toString();
        assertThat(trace.lines().filter(l -> l.startsWith("\tSuppressed: ")).count())
                .isEqualTo(SafeStackTrace.MAX_SUPPRESSED);
        assertThat(trace).doesNotContain("more suppressed omitted").doesNotContain("trace truncated");
    }

    /** A wide tree: 8 suppressed per level, 4 levels (4,681 distinct throwables, far over the length cap). */
    @Test
    void aWideTreeIsCutAtTheLengthCap_quickly() {
        RuntimeException root = wide(0);
        long t0 = System.nanoTime();
        String trace = SafeStackTrace.of(root).toString();
        long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(trace.length()).isLessThanOrEqualTo(SafeStackTrace.MAX_LENGTH + SafeStackTrace.TRUNCATED.length() + 1);
        assertThat(trace).endsWith(SafeStackTrace.TRUNCATED);
        assertThat(trace).startsWith(RuntimeException.class.getName() + "\n");
        assertThat(trace).doesNotContain("leak@x.test");
        assertThat(ms).as("rendering time").isLessThan(2000);
    }

    private static RuntimeException wide(int level) {
        RuntimeException e = new RuntimeException(PII);
        if (level < 4) {
            for (int i = 0; i < 8; i++) {
                e.addSuppressed(wide(level + 1));
            }
        }
        return e;
    }

    @Test
    void manyFramesInEveryCause_areCutAtTheLengthCap() {
        RuntimeException e = null;
        StackTraceElement[] frames = new StackTraceElement[SafeStackTrace.MAX_FRAMES];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("a.very.long.package.name.for.the.Clazz" + i, "method" + i, "Clazz.java", i);
        }
        for (int i = 0; i < SafeStackTrace.MAX_DEPTH; i++) {
            e = new RuntimeException(PII, e);
            e.setStackTrace(frames.clone());
            frames = java.util.Arrays.copyOf(frames, frames.length); // same frames: nothing elided per level...
            frames[frames.length - 1] = new StackTraceElement("x.Distinct" + i, "m", "D.java", i); // ...but the last
        }
        String trace = SafeStackTrace.of(e).toString();
        assertThat(trace.length()).isLessThanOrEqualTo(SafeStackTrace.MAX_LENGTH + SafeStackTrace.TRUNCATED.length() + 1);
        assertThat(trace).endsWith(SafeStackTrace.TRUNCATED).doesNotContain("leak@x.test");
    }

    @Test
    void aTraceUnderTheCapIsNotMarkedTruncated() {
        assertThat(SafeStackTrace.of(failure()).toString()).doesNotContain("trace truncated");
    }

    /** Counts how many throwables the renderer actually walks (it reads each one's frames once). */
    static final class Counting extends RuntimeException {
        static final java.util.concurrent.atomic.AtomicInteger VISITED = new java.util.concurrent.atomic.AtomicInteger();

        Counting() {
            super(PII, null, true, false); // suppression on, no stack trace filled: cheap to build many
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            VISITED.incrementAndGet();
            return super.getStackTrace();
        }
    }

    private static Counting counting(int level, int levels) {
        Counting e = new Counting();
        if (level < levels) {
            for (int i = 0; i < 8; i++) {
                e.addSuppressed(counting(level + 1, levels));
            }
        }
        return e;
    }

    /** 37,449 throwables: once the text is over the cap, the rest of the tree is not walked at all. */
    @Test
    void onceOverTheCap_theRestOfTheTreeIsNotWalked() {
        Counting root = counting(0, 5);
        Counting.VISITED.set(0);
        String trace = SafeStackTrace.of(root).toString();
        assertThat(trace).endsWith(SafeStackTrace.TRUNCATED);
        assertThat(Counting.VISITED.get()).as("throwables walked").isLessThan(1000);
    }

    @Test
    void nullIsRendered() {
        assertThat(SafeStackTrace.of(null)).hasToString("null");
    }
}
