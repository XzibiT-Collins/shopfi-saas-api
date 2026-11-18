package com.example.perfume_budget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

import java.util.regex.Pattern;

/**
 * SEC-01d: what of a Paystack error answer may go into a log line. Never the raw body: besides {@code status} and
 * {@code message} it can carry the customer's e-mail, authorization / access codes and card metadata (bin, last4).
 * Only Paystack's top-level {@code status} (boolean), {@code message} (redacted: e-mail addresses and digit runs of
 * four or more removed, control characters flattened, length capped) and {@code type} / {@code code} (only when they
 * are plain lower-case identifiers) are kept. A body that is not such JSON yields nothing but its absence.
 */
final class PaystackErrorSummary {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"'<>(),;:]+@[^\\s@\"'<>(),;:]+");
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d\\s-]{2,}\\d");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");
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

    /** Paystack's {@code message} without e-mail addresses, numbers that could be card / account data, or line breaks. */
    static String redact(String message) {
        if (message == null) {
            return "null";
        }
        String flat = CONTROL.matcher(message).replaceAll(" ");
        String safe = DIGITS.matcher(EMAIL.matcher(flat).replaceAll("[redacted]")).replaceAll("[redacted]").strip();
        return safe.length() > MAX_MESSAGE ? safe.substring(0, MAX_MESSAGE) + "..." : safe;
    }
}
