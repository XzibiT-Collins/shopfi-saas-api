package com.example.perfume_budget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

import java.util.regex.Pattern;

/**
 * SEC-01d: what of a Paystack error answer may go into a log line. Never the raw body: besides {@code status} and
 * {@code message} it can carry the customer's e-mail, authorization / access codes and card metadata (bin, last4).
 * Only Paystack's top-level {@code status} (boolean), {@code message} (redacted: e-mail addresses, also URL-encoded,
 * and digit runs of four or more removed, every control character and Unicode line break flattened, invisible format
 * characters removed, length capped)
 * and {@code type} / {@code code} (only when they are plain lower-case identifiers) are kept. A body that is not such
 * JSON yields nothing but its absence.
 */
final class PaystackErrorSummary {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** SEC-01e: also a URL-encoded {@code @} ({@code %40}, or {@code %2540} when encoded twice). */
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"'<>(),;:]+(?:@|%(?:25)*40)[^\\s@\"'<>(),;:]+");
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d\\s-]{2,}\\d");
    /**
     * SEC-01e: every control character, C0 and C1 ({@code \p{Cc}}: CR, LF, VT, FF, NEL U+0085, ...), and the Unicode
     * line / paragraph separators (U+2028, U+2029). {@code \p{Cntrl}} alone is ASCII-only and let NEL through.
     */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]+");
    /**
     * SEC-01e rework 1 (F-3): invisible format characters ({@code \p{Cf}}: bidi overrides and isolates U+202A..U+202E,
     * U+2066..U+2069, the BOM / zero-width no-break space U+FEFF, zero-width spaces and joiners, soft hyphen) can
     * reorder or hide what a log viewer shows. They are removed, not turned into a space, so {@code jane<U+200B>@x.com}
     * stays one e-mail address and is redacted.
     */
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}+");
    private static final Pattern CODE = Pattern.compile("[a-z_]{1,64}");
    private static final int MAX_MESSAGE = 160;

    private PaystackErrorSummary() {
    }

    /** {@code "status=false message=Invalid key type=validation_error code=invalid_params"}, or a fixed placeholder. */
    static String of(RestClientResponseException e) {
        JsonNode root;
        try {
            root = MAPPER.readTree(e.getResponseBodyAsByteArray());
        } catch (Exception notJson) {
            return "(no Paystack error fields)";
        }
        if (root == null || !root.isObject()) {
            return "(no Paystack error fields)";
        }
        StringBuilder out = new StringBuilder();
        JsonNode status = root.get("status");
        if (status != null && status.isBoolean()) {
            out.append("status=").append(status.booleanValue());
        }
        JsonNode message = root.get("message");
        if (message != null && message.isTextual()) {
            out.append(out.isEmpty() ? "" : " ").append("message=").append(redact(message.textValue()));
        }
        for (String field : new String[]{"type", "code"}) {
            JsonNode v = root.get(field);
            if (v != null && v.isTextual() && CODE.matcher(v.textValue()).matches()) {
                out.append(out.isEmpty() ? "" : " ").append(field).append('=').append(v.textValue());
            }
        }
        return out.isEmpty() ? "(no Paystack error fields)" : out.toString();
    }

    /**
     * Paystack's {@code message} without e-mail addresses, numbers that could be card / account data, line breaks or
     * invisible format characters, at most {@value #MAX_MESSAGE} characters (plus {@code ...}).
     */
    static String redact(String message) {
        if (message == null) {
            return "null";
        }
        String flat = CONTROL.matcher(FORMAT.matcher(message).replaceAll("")).replaceAll(" ");
        String safe = DIGITS.matcher(EMAIL.matcher(flat).replaceAll("[redacted]")).replaceAll("[redacted]").strip();
        if (safe.length() <= MAX_MESSAGE) {
            return safe;
        }
        // SEC-01e rework 1 (F-3): never cut between the two halves of a surrogate pair (an emoji, say)
        int cut = Character.isHighSurrogate(safe.charAt(MAX_MESSAGE - 1)) ? MAX_MESSAGE - 1 : MAX_MESSAGE;
        return safe.substring(0, cut) + "...";
    }
}
