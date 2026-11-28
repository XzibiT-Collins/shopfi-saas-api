package com.example.perfume_budget.platform.billing.credentials;

import com.example.perfume_budget.utils.AesGcmEncryptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BILL-01 AC5: versioned ciphertext, AAD, rotation, separation from the legacy tenant cipher. */
class BillingSecretCipherTest {

    private static final String SECRET = "sk_test_DOCSEXAMPLEONLYNOTAKEY0002";
    private static final String AAD = BillingSecretCipher.aad("billing_paystack_credentials", "TEST", "secret_key");

    private static MockEnvironment env(String billingKey, int version) {
        MockEnvironment e = new MockEnvironment().withProperty("platform.encryption.key", "platform-key-for-unit-tests-0123456789")
                .withProperty("billing.encryption.key-version", Integer.toString(version));
        if (billingKey != null) {
            e.setProperty("billing.encryption.key", billingKey);
        }
        return e;
    }

    @Test
    void roundTripCarriesTheKeyVersionPrefixAndNeverTheKeyInTheCiphertext() {
        BillingSecretCipher c = new BillingSecretCipher(env("billing-key-for-unit-tests-0123456789ab", 1));
        String ct = c.encrypt(SECRET, AAD);
        assertThat(ct).startsWith("v1:");
        assertThat(BillingSecretCipher.versionOf(ct)).isEqualTo(1);
        assertThat(ct).doesNotContain(SECRET).doesNotContain("DOCSEXAMPLE");
        assertThat(new String(Base64.getDecoder().decode(ct.substring(3)))).doesNotContain("DOCSEXAMPLE");
        assertThat(c.decrypt(ct, AAD)).isEqualTo(SECRET);
    }

    @Test
    void everyEncryptionUsesAFreshIv() {
        BillingSecretCipher c = new BillingSecretCipher(env("billing-key-for-unit-tests-0123456789ab", 1));
        assertThat(c.encrypt(SECRET, AAD)).isNotEqualTo(c.encrypt(SECRET, AAD));
    }

    @Test
    void aMovedCiphertextFailsAuthentication_otherRowOrFieldOrTable() {
        BillingSecretCipher c = new BillingSecretCipher(env("billing-key-for-unit-tests-0123456789ab", 1));
        String ct = c.encrypt(SECRET, AAD);
        for (String other : new String[]{BillingSecretCipher.aad("billing_paystack_credentials", "LIVE", "secret_key"),
                BillingSecretCipher.aad("billing_paystack_credentials", "TEST", "public_key"),
                BillingSecretCipher.aad("other_table", "TEST", "secret_key"), ""}) {
            assertThatThrownBy(() -> c.decrypt(ct, other)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Decryption failed");
        }
    }

    @Test
    void tamperedOrMalformedCiphertextIsRefusedWithAFixedMessage() {
        BillingSecretCipher c = new BillingSecretCipher(env("billing-key-for-unit-tests-0123456789ab", 1));
        String ct = c.encrypt(SECRET, AAD);
        char flipped = ct.charAt(ct.length() - 3) == 'A' ? 'B' : 'A';
        String tampered = ct.substring(0, ct.length() - 3) + flipped + ct.substring(ct.length() - 2);
        for (String bad : new String[]{tampered, "garbage", "v1:", "v1:AAAA", "x1:" + ct.substring(3), "v:abc", ""}) {
            assertThatThrownBy(() -> c.decrypt(bad, AAD)).isInstanceOf(IllegalStateException.class).hasMessage("Decryption failed");
        }
    }

    @Test
    void rotationKeepsOldCiphertextsReadableViaTheRetiredKey() {
        BillingSecretCipher v1 = new BillingSecretCipher(env("old-billing-key-for-unit-tests-0123456789", 1));
        String old = v1.encrypt(SECRET, AAD);
        MockEnvironment rotated = env("new-billing-key-for-unit-tests-0123456789", 2)
                .withProperty("billing.encryption.retired-key.1", "old-billing-key-for-unit-tests-0123456789");
        BillingSecretCipher v2 = new BillingSecretCipher(rotated);
        assertThat(v2.decrypt(old, AAD)).isEqualTo(SECRET);          // the old value still reads
        String fresh = v2.encrypt(SECRET, AAD);
        assertThat(fresh).startsWith("v2:");                          // new writes use the new key
        assertThat(BillingSecretCipher.versionOf(fresh)).isEqualTo(2);
        assertThat(v2.decrypt(fresh, AAD)).isEqualTo(SECRET);
        // without the retired key the old value cannot be read, and the new key does not read it either
        BillingSecretCipher noRetired = new BillingSecretCipher(env("new-billing-key-for-unit-tests-0123456789", 2));
        assertThatThrownBy(() -> noRetired.decrypt(old, AAD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> v1.decrypt(fresh, AAD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theBillingKeyIsSeparateFromThePlatformKey_andFallsBackOnlyWhenBlank() {
        BillingSecretCipher separate = new BillingSecretCipher(env("billing-key-for-unit-tests-0123456789ab", 1));
        BillingSecretCipher fallback = new BillingSecretCipher(env(null, 1));
        String ct = separate.encrypt(SECRET, AAD);
        assertThatThrownBy(() -> fallback.decrypt(ct, AAD)).isInstanceOf(IllegalStateException.class);
        assertThat(fallback.decrypt(fallback.encrypt(SECRET, AAD), AAD)).isEqualTo(SECRET);
    }

    @Test
    void theTenantCipherAndTheBillingCipherDoNotReadEachOther() {
        String platformKey = "platform-key-for-unit-tests-0123456789";
        AesGcmEncryptor legacy = new AesGcmEncryptor(platformKey);
        BillingSecretCipher billing = new BillingSecretCipher(env(null, 1)); // falls back to the SAME passphrase
        String legacyCt = legacy.encrypt(SECRET);
        assertThatThrownBy(() -> billing.decrypt(legacyCt, AAD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> legacy.decrypt(billing.encrypt(SECRET, AAD))).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Regression (AC5): a ciphertext of the CURRENT tenant format (Base64 of iv || ciphertext+tag, no key id, no AAD), made
     * independently of this code (Python AES-GCM over SHA-256("test-encryption-key")), keeps decrypting unchanged.
     */
    @Test
    void legacyTenantCiphertextFixtureStillDecrypts() {
        String fixture = "AQIDBAUGBwgJCgsMU88oECeIAx0qEYOf1PzQuanIeoG1WZwz8iYN5jnglhY8ooVb5n8=";
        assertThat(new AesGcmEncryptor("test-encryption-key").decrypt(fixture)).isEqualTo("sk_test_legacy_fixture");
        assertThat(BillingSecretCipher.versionOf(fixture)).isEqualTo(-1); // the legacy format has no version prefix
    }

    @Test
    void noKeyAtAllFailsAtConstruction() {
        MockEnvironment none = new MockEnvironment();
        assertThatThrownBy(() -> new BillingSecretCipher(none)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void exceptionMessagesNeverContainThePlaintextOrTheKey() {
        BillingSecretCipher c = new BillingSecretCipher(env("billing-key-for-unit-tests-0123456789ab", 1));
        assertThatThrownBy(() -> c.decrypt("v1:" + Base64.getEncoder().encodeToString(new byte[40]), AAD))
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("billing-key").doesNotContain(SECRET);
                    assertThat(e.getCause()).isNull();
                });
    }
}
