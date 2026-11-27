package com.example.perfume_budget.platform.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** API-01 AC3: the bean-validation limits equal the real rules, so OpenAPI-generated types do not under-constrain. */
class TenantSignupRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(String slug, String password) {
        return validator.validate(new TenantSignupRequest(slug, "Shop", "o@shop.test", "Owner", password)).stream()
                .map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void slugMustBeThreeToForty() {
        assertThat(invalidFields("abc", "Correct-Horse-9")).isEmpty();
        assertThat(invalidFields("a".repeat(40), "Correct-Horse-9")).isEmpty();
        assertThat(invalidFields("ab", "Correct-Horse-9")).containsExactly("slug");
        assertThat(invalidFields("a".repeat(41), "Correct-Horse-9")).containsExactly("slug");
    }

    @Test
    void passwordMustBeEightToSeventyTwo() {
        assertThat(invalidFields("abc", "12345678")).isEmpty();
        assertThat(invalidFields("abc", "x".repeat(72))).isEmpty();
        assertThat(invalidFields("abc", "1234567")).containsExactly("password");
        assertThat(invalidFields("abc", "x".repeat(73))).containsExactly("password");
    }
}
