package com.example.perfume_budget.validation;

import com.example.perfume_budget.dto.auth.request.RegistrationRequest;
import com.example.perfume_budget.dto.auth.request.ResetPasswordRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** AUTH-01 AC4: one server-side password policy for registration and reset, evaluated on the stripped value. */
class PasswordPolicyTest {

    private static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsAStrongPassword() {
        assertThat(PasswordPolicy.violation("Abcdef1!")).isNull();
    }

    @Test
    void rejectsTooShortMissingClassesAndTooLong() {
        assertThat(PasswordPolicy.violation("Ab1!xyz")).isEqualTo(PasswordPolicy.MSG_TOO_SHORT); // 7
        assertThat(PasswordPolicy.violation("abcdefg1!")).isEqualTo(PasswordPolicy.MSG_COMPOSITION); // no upper
        assertThat(PasswordPolicy.violation("Abcdefgh!")).isEqualTo(PasswordPolicy.MSG_COMPOSITION); // no digit
        assertThat(PasswordPolicy.violation("Abcdefg12")).isEqualTo(PasswordPolicy.MSG_COMPOSITION); // no special
        assertThat(PasswordPolicy.violation("Aa1!" + "x".repeat(68))).isNull(); // exactly 72 bytes
        assertThat(PasswordPolicy.violation("Aa1!" + "x".repeat(69))).isEqualTo(PasswordPolicy.MSG_TOO_LONG);
        assertThat(PasswordPolicy.violation(null)).isNotNull();
        assertThat(PasswordPolicy.violation("   ")).isNotNull();
    }

    @Test
    void lengthIsUtf16UnitsButTheCapIsUtf8Bytes() {
        // 4 + 35*2 = 74 bytes is too long although it is only 39 UTF-16 units
        assertThat(PasswordPolicy.violation("Aa1!" + "é".repeat(35))).isEqualTo(PasswordPolicy.MSG_TOO_LONG);
        assertThat(PasswordPolicy.violation("Aa1!" + "é".repeat(30))).isNull();
    }

    @Test
    void policyIsEvaluatedOnTheStrippedPassword() {
        // 8 chars only counting the padding: "Ab1!   " stripped is 4 chars -> too short
        assertThat(PasswordPolicy.violation("  Ab1!  ")).isEqualTo(PasswordPolicy.MSG_TOO_SHORT);
        assertThat(PasswordPolicy.violation("  Abcdef1!  ")).isNull();
        assertThat(PasswordPolicy.normalize("  Abcdef1!\t\n")).isEqualTo("Abcdef1!");
    }

    private static Set<String> messages(Object bean) {
        return V.validate(bean).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void registrationRequestEnforcesThePolicy() {
        assertThat(messages(new RegistrationRequest("a@b.test", "A B", "weakpass", "weakpass")))
                .contains(PasswordPolicy.MSG_COMPOSITION);
        assertThat(messages(new RegistrationRequest("a@b.test", "A B", "Ab1!", "Ab1!")))
                .contains(PasswordPolicy.MSG_TOO_SHORT);
        assertThat(messages(new RegistrationRequest("a@b.test", "A B", " Abcdef1! ", " Abcdef1! "))).isEmpty();
    }

    @Test
    void resetPasswordRequestEnforcesThePolicyOnBothFields_withTheOldCompositionMessage() {
        Set<String> m = messages(new ResetPasswordRequest("weakpass", "weakpass"));
        assertThat(m).containsExactly(PasswordPolicy.MSG_COMPOSITION);
        assertThat(messages(new ResetPasswordRequest("Ab1!", "Ab1!"))).containsExactly(PasswordPolicy.MSG_TOO_SHORT);
        assertThat(messages(new ResetPasswordRequest(" Abcdef1! ", "Abcdef1!"))).isEmpty();
    }
}
