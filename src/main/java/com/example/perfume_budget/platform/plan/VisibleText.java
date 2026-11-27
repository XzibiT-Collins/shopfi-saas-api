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
            return value == null || !strip(value).isEmpty();
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
