package com.example.perfume_budget.utils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * EVT-01c rework 1: the sanctioned way to put a stack trace into an INFO/WARN/ERROR log line. A throwable passed to the
 * logger prints its message, and messages can quote personal data (Postgres {@code Key (email)=(...) already exists},
 * a rejected mail recipient), so {@code NoPersonalDataInLogsTest} rejects {@code log.error("..", e)}. This renders the
 * same shape as {@link Throwable#printStackTrace()} (the throwable, its causes and suppressed exceptions, each with its
 * stack frames, frames in common with the enclosing trace elided) but with exception CLASS NAMES only: never
 * {@code getMessage()}, {@code getLocalizedMessage()} or {@code toString()} of a throwable.
 *
 * <p>Usage: {@code log.error("Something failed: {}", SafeStackTrace.of(e));} The text is built only when the line is
 * actually written ({@link #toString()}).
 */
public final class SafeStackTrace {

    static final int MAX_FRAMES = 64;
    static final int MAX_DEPTH = 16;

    private final Throwable throwable;

    private SafeStackTrace(Throwable throwable) {
        this.throwable = throwable;
    }

    public static SafeStackTrace of(Throwable throwable) {
        return new SafeStackTrace(throwable);
    }

    @Override
    public String toString() {
        if (throwable == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        append(out, throwable, new StackTraceElement[0], "", "", seen, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, Throwable t, StackTraceElement[] enclosing, String caption,
                               String prefix, Set<Throwable> seen, int depth) {
        if (!seen.add(t)) {
            out.append(prefix).append(caption).append("[CIRCULAR REFERENCE: ").append(t.getClass().getName()).append("]\n");
            return;
        }
        if (depth > MAX_DEPTH) {
            out.append(prefix).append(caption).append(t.getClass().getName()).append(" (deeper causes omitted)\n");
            return;
        }
        out.append(prefix).append(caption).append(t.getClass().getName()).append('\n');
        StackTraceElement[] trace = t.getStackTrace();
        int m = trace.length - 1;
        int n = enclosing.length - 1;
        while (m >= 0 && n >= 0 && trace[m].equals(enclosing[n])) {
            m--;
            n--;
        }
        int inCommon = trace.length - 1 - m;
        int shown = Math.min(m + 1, MAX_FRAMES);
        for (int i = 0; i < shown; i++) {
            out.append(prefix).append("\tat ").append(trace[i]).append('\n');
        }
        if (shown < m + 1) {
            out.append(prefix).append("\t... ").append(m + 1 - shown).append(" more frames omitted\n");
        }
        if (inCommon != 0) {
            out.append(prefix).append("\t... ").append(inCommon).append(" more\n");
        }
        for (Throwable s : t.getSuppressed()) {
            append(out, s, trace, "Suppressed: ", prefix + "\t", seen, depth + 1);
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            append(out, cause, trace, "Caused by: ", prefix, seen, depth + 1);
        }
    }
}
