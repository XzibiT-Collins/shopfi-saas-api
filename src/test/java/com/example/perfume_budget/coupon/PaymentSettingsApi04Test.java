package com.example.perfume_budget.coupon;

import com.example.perfume_budget.dto.payment.request.PaymentSettingsRequest;
import com.example.perfume_budget.dto.payment.response.PaymentSettingsResponse;
import com.example.perfume_budget.model.PaymentGatewayConfig;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.repository.PaymentGatewayConfigRepository;
import com.example.perfume_budget.service.PaymentSettingsServiceImpl;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.utils.AesGcmEncryptor;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** API-04: key format checks, no secret in toString / validation messages, webhook-token rotation. */
class PaymentSettingsApi04Test {

    private static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String SK = "sk_test_FAKEabcdef123456";
    private static final String PK = "pk_test_FAKEabcdef123456";

    private static Set<String> msgs(PaymentSettingsRequest r) {
        return V.validate(r).stream().map(v -> v.getMessage()).collect(Collectors.toSet());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void validKeysAccepted_blankSecretMeansKeep() {
        assertThat(msgs(new PaymentSettingsRequest(SK, PK, true))).isEmpty();
        assertThat(msgs(new PaymentSettingsRequest("sk_live_ABCDEFGH1234", "pk_live_ABCDEFGH1234", true))).isEmpty();
        assertThat(msgs(new PaymentSettingsRequest("", PK, true))).isEmpty();
        assertThat(msgs(new PaymentSettingsRequest(null, PK, false))).isEmpty();
        assertThat(msgs(new PaymentSettingsRequest("  ", PK, false))).isEmpty();
    }

    @Test
    void badSecretShapesRejected() {
        for (String bad : new String[]{"pk_test_FAKEabcdef", "sk_foo_abcdefgh", "sk_test_", "sk_test_ab", "hello", "sk_test_abc def",
                "sk_test_abcdefgh\n; DROP", "SK_TEST_ABCDEFGH", "sk_test_" + "a".repeat(201)}) {
            assertThat(msgs(new PaymentSettingsRequest(bad, PK, true))).as("secret [%s]", bad)
                    .contains("Secret Key must look like sk_test_... or sk_live_...");
        }
    }

    @Test
    void badPublicShapesRejected() {
        for (String bad : new String[]{SK, "pk_test_", "pk_x_abcdefgh", "abc", "pk_test_abc def"}) {
            assertThat(msgs(new PaymentSettingsRequest(SK, bad, true))).as("public [%s]", bad)
                    .contains("Public Key must look like pk_test_... or pk_live_...");
        }
        assertThat(msgs(new PaymentSettingsRequest(SK, "  ", true))).contains("Public Key is required");
        assertThat(msgs(new PaymentSettingsRequest(SK, null, true))).contains("Public Key is required");
    }

    @Test
    void mixedModesRejected() {
        assertThat(msgs(new PaymentSettingsRequest(SK, "pk_live_ABCDEFGH1234", true)))
                .containsExactly("Secret Key and Public Key must both be test keys or both be live keys");
        assertThat(msgs(new PaymentSettingsRequest("sk_live_ABCDEFGH1234", PK, true)))
                .containsExactly("Secret Key and Public Key must both be test keys or both be live keys");
    }

    @Test
    void validationMessagesNeverEchoTheSubmittedValue() {
        String leaked = "sk_test_SUPERSECRET-not-valid!!";
        assertThat(msgs(new PaymentSettingsRequest(leaked, PK, true))).allSatisfy(m -> assertThat(m).doesNotContain("SUPERSECRET"));
    }

    @Test
    void toStringRedactsKeys() {
        String s = new PaymentSettingsRequest(SK, PK, true).toString();
        assertThat(s).doesNotContain(SK).doesNotContain(PK).doesNotContain("FAKEabcdef").contains("<redacted>").contains("enabled=true");
        assertThat(new PaymentSettingsRequest(null, PK, false).toString()).contains("secretKey=<none>");
        assertThat(new PaymentSettingsRequest(" ", PK, false).toString()).contains("secretKey=<none>");
    }

    @Test
    void rotateMintsNewTokenAndReturnsNewUrl_secretNeverInResponse() {
        PaymentGatewayConfigRepository repo = mock(PaymentGatewayConfigRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        AesGcmEncryptor enc = mock(AesGcmEncryptor.class);
        PaymentSettingsServiceImpl service = new PaymentSettingsServiceImpl(repo, tenants, enc);
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.test");
        Tenant t = new Tenant();
        t.setSlug("acme");
        t.setWebhookToken("OLD-TOKEN");
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(t));
        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setSecretKeyEncrypted("ENCRYPTED");
        cfg.setPublicKey(PK);
        cfg.setEnabled(true);
        when(repo.findTopByOrderByIdAsc()).thenReturn(Optional.of(cfg));
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));

        PaymentSettingsResponse r = service.rotateWebhookToken();

        assertThat(t.getWebhookToken()).isNotEqualTo("OLD-TOKEN").hasSize(43);
        assertThat(r.webhookUrl()).isEqualTo("https://api.test/api/v1/webhook/" + t.getWebhookToken());
        assertThat(r.secretConfigured()).isTrue();
        assertThat(r.publicKey()).isEqualTo(PK);
        assertThat(r.enabled()).isTrue();
        verify(tenants).save(t);
        assertThat(r.toString()).doesNotContain("ENCRYPTED");
        // two rotations never repeat
        String first = t.getWebhookToken();
        service.rotateWebhookToken();
        assertThat(t.getWebhookToken()).isNotEqualTo(first);
    }
}
