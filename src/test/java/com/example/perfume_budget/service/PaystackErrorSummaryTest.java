package com.example.perfume_budget.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-01e: the parts of {@link PaystackErrorSummary} the gateway tests do not pin. (F1) the 160-character cap on
 * Paystack's {@code message} and the lower-case identifier allow-list for {@code type} / {@code code}; (F3) every
 * Unicode line break (NEL U+0085, LS U+2028, PS U+2029, VT, FF and the C1 controls) is flattened so a message cannot
 * forge a second log line, and a URL-encoded e-mail address ({@code %40}) is redacted like a plain one.
 */
class PaystackErrorSummaryTest {

    private static HttpClientErrorException answer(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // ---- F1: length cap ----

    @Test
    void aMessageOfExactly160CharactersIsKeptWhole() {
        String m = "a".repeat(160);
        assertThat(PaystackErrorSummary.redact(m)).isEqualTo(m);
    }

    @Test
    void aLongerMessageIsCutAt160CharactersAndMarked() {
        String m = "b".repeat(160) + "TAIL-NEVER-LOGGED" + "c".repeat(500);
        assertThat(PaystackErrorSummary.redact(m)).isEqualTo("b".repeat(160) + "...");
    }

    @Test
    void theCapAppliesToTheSummaryLine() {
        String summary = PaystackErrorSummary.of(answer("{\"status\":false,\"message\":\"" + "x".repeat(5000) + "\"}"));
        assertThat(summary).isEqualTo("status=false message=" + "x".repeat(160) + "...");
    }

    // ---- F1: type / code allow-list ----

    @Test
    void plainLowerCaseIdentifiersAreKept_upTo64Characters() {
        String code64 = "a".repeat(64);
        String summary = PaystackErrorSummary.of(answer(
                "{\"status\":false,\"type\":\"validation_error\",\"code\":\"" + code64 + "\"}"));
        assertThat(summary).isEqualTo("status=false type=validation_error code=" + code64);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Invalid-Code", "invalid_params<script>", "probe-leak@x.test", "has space", "line\nERROR forged",
            "UPPER", "digits123", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" /* 65 */})
    void anythingElseInTypeOrCodeIsDropped(String value) {
        String summary = PaystackErrorSummary.of(answer(
                "{\"status\":false,\"type\":\"" + json(value) + "\",\"code\":\"" + json(value) + "\"}"));
        assertThat(summary).isEqualTo("status=false");
    }

    @Test
    void aNonTextTypeOrCodeIsDropped() {
        String summary = PaystackErrorSummary.of(answer("{\"type\":{\"x\":\"y\"},\"code\":42}"));
        assertThat(summary).isEqualTo("(no Paystack error fields)");
    }

    // ---- F3: every line break is flattened ----

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n", "\u000B", "\u000C", "\u0085", " ", " ", "\u009B", "\u001B", "\u0000"})
    void noLineBreakOrControlCharacterSurvives(String separator) {
        String redacted = PaystackErrorSummary.redact("first" + separator + "ERROR forged line");
        assertThat(redacted).isEqualTo("first ERROR forged line");
        assertThat(redacted.codePoints()).noneMatch(c -> Character.getType(c) == Character.CONTROL
                || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR);
    }

    @Test
    void aNelInsideTheSummaryCannotForgeALine() {
        String summary = PaystackErrorSummary.of(answer("{\"message\":\"ok\\u0085ERROR forged\\u2028more\"}"));
        assertThat(summary).isEqualTo("message=ok ERROR forged more");
    }

    // ---- F3: URL-encoded e-mail addresses ----

    @ParameterizedTest
    @ValueSource(strings = {"probe%40x.test", "Probe.User+tag%40Example.COM", "probe%2540x.test", "probe@x.test"})
    void anEncodedEmailAddressIsRedacted(String address) {
        assertThat(PaystackErrorSummary.redact("Customer " + address + " not found")).isEqualTo("Customer [redacted] not found");
    }

    @Test
    void textWithoutAnAddressIsLeftAlone() {
        assertThat(PaystackErrorSummary.redact("Discount of 40% applied; 100%")).isEqualTo("Discount of 40% applied; 100%");
    }
}
