package com.example.perfume_budget.platform.billing.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Number formats (billing.md 4.1; section 21 item 21 of BILL-00): closed charset, tokens, LITERAL substitution. */
class NumberFormatsTest {

    @Test
    void rendersBothTokensLiterallyAndPadsTheCounter() {
        assertThat(NumberFormats.render("SUB-{YYYY}-{NNNNNN}", 2026, 1)).isEqualTo("SUB-2026-000001");
        assertThat(NumberFormats.render("INV/{YYYY}/{NNNNNN}", 2027, 123456)).isEqualTo("INV/2027/123456");
        assertThat(NumberFormats.render("X{NNNNNN}{NNNNNN}", 2026, 7)).isEqualTo("X000007000007");
    }

    @Test
    void theCounterGrowsBeyondSixDigitsAndIsNeverTruncated() {
        assertThat(NumberFormats.render("N-{NNNNNN}", 2026, 1_234_567)).isEqualTo("N-1234567");
    }

    @Test
    void metaCharactersOfFormatAndRegexAreInert() {
        // $1, \\, %s and {0} can never reach render through the API (the charset refuses them), but the substitution itself
        // must be literal: a literal part full of regex / format meta characters survives untouched
        assertThat(NumberFormats.render("A.$1\\%s{0}-{NNNNNN}", 2026, 5)).isEqualTo("A.$1\\%s{0}-000005");
        assertThat(NumberFormats.render("{YYYY}.*{NNNNNN}", 2026, 5)).isEqualTo("2026.*000005");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUB-{YYYY}-{NNNNNN}", "INV/{YYYY}/{NNNNNN}", "{NNNNNN}", "{NNNNNN}-ABC", "A_B.C-{NNNNNN}", "ABCDEF{NNNNNN}"})
    void acceptsValidFormats(String f) {
        assertThat(NumberFormats.isValid(f)).as(f).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INV-{YYYY}",                 // no counter
            "INV {NNNNNN}",               // space
            "INV-%s-{NNNNNN}", "INV-$1-{NNNNNN}", "INV-\\-{NNNNNN}", "INV-(x)-{NNNNNN}", "INV-*-{NNNNNN}", // outside the charset
            "INV-{MM}-{NNNNNN}", "INV-{}-{NNNNNN}", "INV-{YYYY-{NNNNNN}", "INV-}-{NNNNNN}", // stray braces
            "INV-é-{NNNNNN}", "INV-{NNNNNN}-‮", // non ASCII, bidi override
            "INV-{YYYY}-{NNNNNN}-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123" // 41 chars
    })
    void refusesInvalidFormats(String f) {
        assertThat(NumberFormats.isValid(f)).as(f).isFalse();
    }

    @Test
    void theLiteralPartIsTheFormatWithoutTokens() {
        assertThat(NumberFormats.literalPart("SUB-{YYYY}-{NNNNNN}")).isEqualTo("SUB--");
    }

    /** Source guard: the substitution never goes through String.format, MessageFormat or a regex replacement. */
    @Test
    void renderingSourceUsesNoFormatOrRegexApi() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/example/perfume_budget/platform/billing/settings/NumberFormats.java"));
        String render = src.substring(src.indexOf("public static String render"));
        assertThat(render).doesNotContain("String.format", "MessageFormat", "replaceAll", "replaceFirst", "Matcher", ".formatted(");
        assertThat(render).contains(".replace(YEAR_TOKEN").contains(".replace(COUNTER_TOKEN");
    }
}
