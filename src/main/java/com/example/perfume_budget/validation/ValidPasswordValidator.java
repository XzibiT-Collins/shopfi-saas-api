package com.example.perfume_budget.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidPasswordValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String violation = PasswordPolicy.violation(value);
        if (violation == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(violation.replace("{", "\\{").replace("}", "\\}"))
                .addConstraintViolation();
        return false;
    }
}
