package com.example.perfume_budget.platform.plan;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** BILL-01c (PLAN-01 Security low): a required text needs a VISIBLE character; blank-rendering characters do not count. */
class VisibleTextTest {

    private static boolean valid(String s) {
        return new VisibleText.Validator().isValid(s, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", " ", " 　", "\t\n",
            "ㅤ", "ㅤㅤㅤ", " ㅤ ",            // Hangul filler
            "⠀", "⠀⠀",                              // braille pattern blank
            "ᅟᅠ", "ﾠ",                              // other Hangul fillers
            "́", "́̂̃", "⃝",              // combining only (Mn, Me)
            "ः",                                              // combining spacing mark (Mc) alone
            "​", "‍", "⁠", "﻿", "­",      // zero-width / format (Cf)
            "឴឵", "᠎", "͏",                    // Khmer inherent vowels, Mongolian separator, CGJ
            "⁣⁤",                                        // invisible separator / plus
            "",                                              // private use
            "\u0000", "\u001F", "\u007F"                           // controls
    })
    void rejectsTextWithoutAVisibleCharacter(String s) {
        assertThat(valid(s)).as("U+%04X... must be rejected", s.isEmpty() ? 0 : s.codePointAt(0)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Pro", "A", "9", "Café", "Café", "专业版", "का",   // base letter + mark
            "👍", "Aㅤ", "ㅤA", "⠀x", "—", "%"
    })
    void acceptsTextWithAtLeastOneVisibleCharacter(String s) {
        assertThat(valid(s)).isTrue();
    }

    @org.junit.jupiter.api.Test
    void nullIsValid_combineWithNotNull() {
        assertThat(valid(null)).isTrue();
    }

    @org.junit.jupiter.api.Test
    void anInvisibleOnlyOptionalTextIsStoredAsNull() {
        // optional texts (tagline, description, badge, heading) follow the same rule as blank
        assertThat(VisibleText.Validator.hasVisible("ㅤ⠀")).isFalse();
        assertThat(VisibleText.Validator.hasVisible("aㅤ")).isTrue();
    }
}
