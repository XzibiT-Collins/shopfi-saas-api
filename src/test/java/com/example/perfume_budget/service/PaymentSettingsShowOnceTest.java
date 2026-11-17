package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaymentSettingsRequest;
import com.example.perfume_budget.dto.payment.response.PaymentSettingsResponse;
import com.example.perfume_budget.model.PaymentGatewayConfig;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.repository.PaymentGatewayConfigRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.utils.AesGcmEncryptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** API-06: the webhook URL is shown once, by the call that minted its token; GET only says one exists (masked). */
class PaymentSettingsShowOnceTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_wxyz";
    private static final String BASE = "https://api.test/api/v1/webhook/";
    private final PaymentGatewayConfigRepository repo = mock(PaymentGatewayConfigRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AesGcmEncryptor enc = mock(AesGcmEncryptor.class);
    private final PaymentSettingsServiceImpl service = new PaymentSettingsServiceImpl(repo, tenants, enc, new PassThroughLockTimeout());
    private final Tenant tenant = new Tenant();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.test");
        tenant.setSlug("acme");
        when(tenants.lockBySlug("acme")).thenReturn(Optional.of(tenant));
        when(tenants.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(enc.encrypt(any())).thenReturn("ENCRYPTED");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setSecretKeyEncrypted("ENCRYPTED");
        cfg.setPublicKey("pk_test_FAKEabcdef123456");
        cfg.setEnabled(true);
        when(repo.findTopByOrderByIdAsc()).thenReturn(Optional.of(cfg));
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static PaymentSettingsRequest req() {
        return new PaymentSettingsRequest("sk_test_FAKEabcdef123456", "pk_test_FAKEabcdef123456", true);
    }

    @Test
    void getNeverReturnsTheUrl_onlyThatOneExistsAndAMaskedForm() {
        tenant.setWebhookToken(TOKEN);
        PaymentSettingsResponse r = service.get();
        assertThat(r.webhookUrl()).isNull();
        assertThat(r.hasWebhookToken()).isTrue();
        assertThat(r.webhookUrlMasked()).isEqualTo(BASE + "****wxyz").doesNotContain(TOKEN.substring(0, 39));
        assertThat(r.toString()).doesNotContain(TOKEN);
    }

    @Test
    void getWithoutAToken_hasWebhookTokenFalse_nothingMasked() {
        for (String none : new String[]{null, "", "   "}) {
            tenant.setWebhookToken(none);
            PaymentSettingsResponse r = service.get();
            assertThat(r.hasWebhookToken()).as("[%s]", none).isFalse();
            assertThat(r.webhookUrl()).isNull();
            assertThat(r.webhookUrlMasked()).isNull();
        }
    }

    @Test
    void firstSaveMintsAndShowsTheUrlOnce_laterSavesDoNot() {
        PaymentSettingsResponse first = service.save(req());
        String minted = tenant.getWebhookToken();
        assertThat(minted).hasSize(43);
        assertThat(first.webhookUrl()).isEqualTo(BASE + minted);
        assertThat(first.hasWebhookToken()).isTrue();
        verify(tenants).save(tenant);

        PaymentSettingsResponse again = service.save(req());
        assertThat(tenant.getWebhookToken()).as("a re-save never re-mints").isEqualTo(minted);
        assertThat(again.webhookUrl()).isNull();
        assertThat(again.hasWebhookToken()).isTrue();
        assertThat(again.webhookUrlMasked()).isEqualTo(BASE + "****" + minted.substring(39));
        verify(tenants, times(1)).save(tenant);
    }

    @Test
    void saveOverABlankStoredTokenMintsAndShows() {
        tenant.setWebhookToken("  ");
        PaymentSettingsResponse r = service.save(req());
        assertThat(tenant.getWebhookToken()).hasSize(43);
        assertThat(r.webhookUrl()).isEqualTo(BASE + tenant.getWebhookToken());
    }

    @Test
    void rotateAlwaysShowsTheNewUrl_thenGetMasksIt() {
        tenant.setWebhookToken(TOKEN);
        PaymentSettingsResponse r = service.rotateWebhookToken();
        String fresh = tenant.getWebhookToken();
        assertThat(fresh).isNotEqualTo(TOKEN);
        assertThat(r.webhookUrl()).isEqualTo(BASE + fresh);
        assertThat(r.webhookUrlMasked()).isEqualTo(BASE + "****" + fresh.substring(39));
        assertThat(service.get().webhookUrl()).isNull();
    }

    @Test
    void toStringNeverPrintsTheFullUrl() {
        PaymentSettingsResponse r = new PaymentSettingsResponse(true, "pk", true, BASE + TOKEN, true, BASE + "****wxyz");
        assertThat(r.toString()).doesNotContain(TOKEN).contains("webhookUrl=<redacted>").contains("hasWebhookToken=true");
        assertThat(new PaymentSettingsResponse(false, null, false, null, false, null).toString()).contains("webhookUrl=<none>");
    }
}
