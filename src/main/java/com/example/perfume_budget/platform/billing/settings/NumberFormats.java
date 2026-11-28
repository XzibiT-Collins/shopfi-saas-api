package com.example.perfume_budget.platform.billing.settings;

import java.util.regex.Pattern;

/**
 * Invoice, receipt and credit-note number formats (billing.md 4.1, S9 item 21 of section 21). A format is 8..40 characters
 * of {@code A-Za-z0-9 / _ . - { }}, contains {@code {NNNNNN}} and no other braces than the two tokens {@code {YYYY}} and
 * {@code {NNNNNN}}.
 *
 * <p>Rendering is a LITERAL substitution ({@link String#replace(CharSequence, CharSequence)}), never
 * {@code String.format}, {@code MessageFormat} or a regex replacement: an operator-typed format is data, and a stray
 * {@code %} or {@code $1} in it must stay inert (the BILL-00 Security note on number previews).
 */
public final class NumberFormats {

    public static final String YEAR_TOKEN = "{YYYY}";
    public static final String COUNTER_TOKEN = "{NNNNNN}";
    public static final String MESSAGE = "must contain {NNNNNN} and only letters, digits and - / _ . { }";

    private static final Pattern CHARSET = Pattern.compile("[A-Za-z0-9/_.{}-]{8,40}");

    private NumberFormats() {
    }

    public static boolean isValid(String format) {
        if (format == null || !CHARSET.matcher(format).matches() || !format.contains(COUNTER_TOKEN)) {
            return false;
        }
        return !literalPart(format).contains("{") && !literalPart(format).contains("}");
    }

    /** The format with both tokens removed: what the three formats must not share (they must differ here). */
    public static String literalPart(String format) {
        return format.replace(YEAR_TOKEN, "").replace(COUNTER_TOKEN, "");
    }

    /** The counter zero-padded to 6 digits; it grows beyond 6 digits when needed, never truncates. */
    public static String render(String format, int year, long counter) {
        String digits = Long.toString(counter);
        String padded = "0".repeat(Math.max(0, 6 - digits.length())) + digits;
        return format.replace(YEAR_TOKEN, Integer.toString(year)).replace(COUNTER_TOKEN, padded);
    }
}
