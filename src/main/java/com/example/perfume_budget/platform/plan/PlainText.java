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
 * Plain single-line text (docs/api/plans.md section 1, Security L2): rejects every code point of the Unicode general
 * categories Cc (controls, incl. C1 U+0080-009F) and Cf (format: bidi overrides U+202A-202E, isolates U+2066-2069,
 * zero-width U+200B-200F, soft hyphen, BOM, astral tags ...) and U+2028 / U+2029. Checked on the RAW value, before any
 * trimming (a leading tab or U+2028 must be refused, not stripped away). {@code null} is valid (use {@code @NotNull}).
 */
@Documented
@Constraint(validatedBy = PlainText.Validator.class)
@Target({ElementType.FIELD, ElementType.TYPE_USE, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlainText {

    String message() default "must be plain text (no control, format or line-separator characters)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PlainText, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || isPlain(value);
        }

        /** True when no code point of {@code s} is Cc, Cf, U+2028 or U+2029. */
        public static boolean isPlain(String s) {
            return s.codePoints().noneMatch(cp -> {
                int type = Character.getType(cp);
                return type == Character.CONTROL || type == Character.FORMAT || cp == 0x2028 || cp == 0x2029;
            });
        }
    }
}
