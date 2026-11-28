package com.example.perfume_budget.platform.billing.credentials;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for the platform's own billing secrets (BILL-01 AC5, S2). Unlike {@code AesGcmEncryptor} (tenant gateway
 * secrets, no key id, no AAD, left untouched so legacy ciphertexts keep decrypting) this cipher
 * <ul>
 *   <li>prefixes every ciphertext with the key version: {@code v<version>:<base64(iv || ciphertext+tag)>}, so a key can
 *       be rotated by configuring a new version and keeping the old one as {@code billing.encryption.retired-key.<n>};</li>
 *   <li>binds every ciphertext to its location with AAD (for example {@code billing_paystack_credentials|TEST|secret_key}):
 *       a ciphertext copied to another row or field fails authentication;</li>
 *   <li>uses its own key {@code billing.encryption.key}; only when that is blank does it fall back to
 *       {@code platform.encryption.key} (startup refuses a weak key outside dev and test, see BillingStartupGuard).</li>
 * </ul>
 * The key is derived per version with HMAC-SHA-256 over a fixed label, so the platform key and the billing key never
 * yield the same AES key even when the passphrase is shared. Exception messages are fixed text: never the plaintext, key or
 * ciphertext.
 */
@Component
public class BillingSecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Environment env;
    private final int currentVersion;
    private final SecureRandom random = new SecureRandom();

    public BillingSecretCipher(Environment env) {
        this.env = env;
        this.currentVersion = env.getProperty("billing.encryption.key-version", Integer.class, 1);
        if (currentVersion < 1 || currentVersion > 32000) {
            throw new IllegalStateException("billing.encryption.key-version must be between 1 and 32000");
        }
        keyFor(currentVersion); // fail at startup when the current key is missing
    }

    public int currentVersion() {
        return currentVersion;
    }

    public String encrypt(String plaintext, String aad) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "v" + currentVersion + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed");
        }
    }

    public String decrypt(String ciphertext, String aad) {
        try {
            int sep = ciphertext.indexOf(':');
            if (sep < 2 || ciphertext.charAt(0) != 'v') {
                throw new IllegalStateException("Decryption failed");
            }
            int version = Integer.parseInt(ciphertext.substring(1, sep));
            byte[] all = Base64.getDecoder().decode(ciphertext.substring(sep + 1));
            if (all.length <= IV_LENGTH) {
                throw new IllegalStateException("Decryption failed");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyFor(version), new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed");
        }
    }

    /** The key version a stored ciphertext was written with (for the rotation report), or -1 when it has no prefix. */
    public static int versionOf(String ciphertext) {
        try {
            int sep = ciphertext.indexOf(':');
            return sep >= 2 && ciphertext.charAt(0) == 'v' ? Integer.parseInt(ciphertext.substring(1, sep)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** AAD of one secret field of one credentials row. */
    public static String aad(String table, String rowKey, String field) {
        return table + "|" + rowKey + "|" + field;
    }

    private SecretKeySpec keyFor(int version) {
        String passphrase;
        if (version == currentVersion) {
            String own = env.getProperty("billing.encryption.key", "");
            passphrase = own.isBlank() ? env.getProperty("platform.encryption.key", "") : own;
        } else {
            passphrase = env.getProperty("billing.encryption.retired-key." + version, "");
        }
        if (passphrase.isBlank()) {
            throw new IllegalStateException("No billing encryption key is configured for version " + version);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(passphrase.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(("shopfi.billing.secret.v" + version).getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Key derivation failed");
        }
    }
}
