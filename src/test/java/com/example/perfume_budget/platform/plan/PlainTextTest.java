package com.example.perfume_budget.platform.plan;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** plans.md section 1 / Security L2: Cc, Cf, U+2028 and U+2029 are refused anywhere in a plan text; everything else is fine. */
class PlainTextTest {

    @ParameterizedTest
    @ValueSource(ints = {0x0000, 0x0009, 0x000A, 0x000D, 0x001F, 0x007F, 0x0080, 0x0085, 0x009F, // Cc incl. C1
            0x00AD, 0x061C, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2060, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF, 0xFFF9, 0xFFFB,      // Cf (BMP)
            0x110BD, 0x1D173, 0xE0001, 0xE0020, 0xE007F,                        // Cf (astral)
            0x2028, 0x2029})                                                     // line / paragraph separators
    void forbiddenCodePointsAreRejected_atStartMiddleAndEnd(int cp) {
        String c = new String(Character.toChars(cp));
        for (String s : new String[]{c + "Growth", "Gro" + c + "wth", "Growth" + c, c}) {
            assertThat(PlainText.Validator.isPlain(s)).as("U+%04X in %s", cp, s.replace(c, "<c>")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Growth", "Most popular", "Everything in Starter, plus:", "Up to 1,000 products & more",
            "Crème brûlée", "مرحبا", "中文", "Rocket 🚀",
            "GHS 1,430.40 / yr", "<b>not html</b>", "  padded  ", " nbsp"})
    void ordinaryTextIsAccepted(String s) {
        assertThat(PlainText.Validator.isPlain(s)).isTrue();
    }
}
