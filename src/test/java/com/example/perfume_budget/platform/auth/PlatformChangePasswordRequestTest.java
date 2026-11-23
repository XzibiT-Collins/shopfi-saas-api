package com.example.perfume_budget.platform.auth;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** API-RD-07: BCrypt reads 72 BYTES, so a multibyte password under 72 characters but over 72 bytes is refused up front. */
class PlatformChangePasswordRequestTest {

    static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void multibytePasswordsOver72BytesAreInvalid_inEitherField() {
        String long2byte = "é".repeat(40); // 40 characters, 80 bytes
        assertThat(V.validate(new PlatformChangePasswordRequest(long2byte, "Aa1!aaaaaa"))).isNotEmpty();
        assertThat(V.validate(new PlatformChangePasswordRequest("Aa1!aaaaaa", long2byte))).isNotEmpty();
    }

    @Test
    void ordinaryAndExactly72ByteValuesAreValid() {
        assertThat(V.validate(new PlatformChangePasswordRequest("Aa1!aaaaaa", "Bb2@bbbbbb"))).isEmpty();
        assertThat(V.validate(new PlatformChangePasswordRequest("a".repeat(72), "b".repeat(72)))).isEmpty();
        assertThat(V.validate(new PlatformChangePasswordRequest("a".repeat(73), "b"))).isNotEmpty();
    }

    @Test
    void blankIsInvalid_andToStringMasksPasswords() {
        assertThat(V.validate(new PlatformChangePasswordRequest(" ", "x"))).isNotEmpty();
        assertThat(new PlatformChangePasswordRequest("secret1", "secret2").toString()).doesNotContain("secret");
    }
}
