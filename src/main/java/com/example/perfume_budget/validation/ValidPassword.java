package com.example.perfume_budget.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The value must satisfy {@link PasswordPolicy} (evaluated on the stripped password). Null is left to {@code @NotBlank}. */
@Documented
@Constraint(validatedBy = ValidPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {
    String message() default PasswordPolicy.MSG_COMPOSITION;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
