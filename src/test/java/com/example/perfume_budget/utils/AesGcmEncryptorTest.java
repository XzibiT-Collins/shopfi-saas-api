package com.example.perfume_budget.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEncryptorTest {

    private final AesGcmEncryptor encryptor = new AesGcmEncryptor("unit-test-key");

    @Test
    void encryptThenDecrypt_roundTrips() {
        String secret = "sk_test_paystack_1234567890";
        String encrypted = encryptor.encrypt(secret);

        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(encryptor.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void encrypt_usesRandomIv_soCiphertextDiffersEachTime() {
        String secret = "sk_test_same_value";
        assertThat(encryptor.encrypt(secret)).isNotEqualTo(encryptor.encrypt(secret));
    }

    @Test
    void decrypt_withDifferentKey_fails() {
        String encrypted = encryptor.encrypt("sk_test_value");
        AesGcmEncryptor other = new AesGcmEncryptor("a-different-key");

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
