package com.example.perfume_budget.platform.plan;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A required plan text must contain at least one visible character (QA M1): a value made only of Unicode spaces (U+0020,
 * NBSP U+00A0, em space U+2003, ideographic space U+3000, any {@code \p{Z}} or Java whitespace) is blank. Bean
 * validation's {@code @NotBlank} only trims characters <= U+0020, so it would let those through and the stored text would
 * be empty after {@link #strip(String)}. {@code null} is valid (combine with {@code @NotNull}).
 *
 * <p>BILL-01c: the value must also contain at least one VISIBLE character. Besides spaces, a character is not visible when
 * it is a control, format, unassigned, surrogate or private-use code point, a combining mark (Mn / Mc / Me: alone it
 * draws nothing), or one of the known blank-rendering letters and symbols: U+3164 Hangul filler, U+115F / U+1160 / U+FFA0
 * fillers, U+2800 braille pattern blank, U+17B4 / U+17B5 Khmer inherent vowels, U+180E, U+034F. A text of only such
 * characters (U+3164 repeated, U+2800, a lone combining accent, ...) is rejected like an empty one.
 */
@Documented
@Constraint(validatedBy = VisibleText.Validator.class)
@Target({ElementType.FIELD, ElementType.TYPE_USE, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface VisibleText {

    String message() default "must not be blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<VisibleText, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || hasVisible(value);
        }

        /** True when at least one code point of the text draws something. */
        public static boolean hasVisible(String s) {
            return s.codePoints().anyMatch(Validator::isVisible);
        }

        /** Code points that render nothing although they are not spaces. */
        private static final java.util.Set<Integer> BLANK_RENDERING = java.util.Set.of(
                0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x17B4, 0x17B5, 0x180E, 0x034F, 0x00AD, 0x061C, 0x2063, 0x2064);

        /** True when the code point draws something: not a space, not an invisible or mark-only character. */
        static boolean isVisible(int cp) {
            if (isSpace(cp) || BLANK_RENDERING.contains(cp)) {
                return false;
            }
            return switch (Character.getType(cp)) {
                case Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.SURROGATE, Character.PRIVATE_USE,
                     Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> false;
                default -> true;
            };
        }

        /** True for every Unicode space or whitespace character (Zs, Zl, Zp, and Java whitespace incl. tab / newline). */
        static boolean isSpace(int cp) {
            return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
        }

        /** Removes leading and trailing Unicode spaces (unlike {@link String#strip()}, also NBSP and friends). */
        public static String strip(String s) {
            int start = 0;
            int end = s.length();
            while (start < end && isSpace(s.codePointAt(start))) {
                start += Character.charCount(s.codePointAt(start));
            }
            while (end > start && isSpace(s.codePointBefore(end))) {
                end -= Character.charCount(s.codePointBefore(end));
            }
            return s.substring(start, end);
        }
    }
}
