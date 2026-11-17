package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaymentSettingsRequest;
import com.example.perfume_budget.dto.payment.response.PaymentSettingsResponse;
import com.example.perfume_budget.enums.PaymentProvider;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.PaymentGatewayConfig;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.repository.PaymentGatewayConfigRepository;
import com.example.perfume_budget.service.interfaces.PaymentSettingsService;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.utils.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Manages a tenant's own Paystack credentials. Writes the encrypted secret into the tenant
 * schema and (once) mints the opaque webhook token on the master tenant row. Both writes share
 * one transaction — the master entity is schema-qualified, the config entity follows the tenant
 * search_path.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettingsServiceImpl implements PaymentSettingsService {

    private final PaymentGatewayConfigRepository configRepository;
    private final TenantRepository tenantRepository;
    private final AesGcmEncryptor encryptor;
    private final DbLockTimeout lockTimeout;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${platform.api-base-url}")
    private String apiBaseUrl;

    @Override
    @Transactional
    public PaymentSettingsResponse save(PaymentSettingsRequest request) {
        PaymentGatewayConfig config = configRepository.findTopByOrderByIdAsc()
                .orElseGet(PaymentGatewayConfig::new);
        config.setProvider(PaymentProvider.PAYSTACK);
        if (request.secretKey() != null && !request.secretKey().isBlank()) {
            config.setSecretKeyEncrypted(encryptor.encrypt(request.secretKey().strip()));
        } else if (config.getSecretKeyEncrypted() == null || config.getSecretKeyEncrypted().isBlank()) {
            throw new BadRequestException("Secret Key is required");
        }
        config.setPublicKey(request.publicKey().strip());
        config.setEnabled(request.enabled());
        config = configRepository.save(config);

        Tenant tenant = lockCurrentTenant();
        boolean minted = false;
        if (tenant.getWebhookToken() == null || tenant.getWebhookToken().isBlank()) {
            tenant.setWebhookToken(generateToken());
            tenantRepository.save(tenant);
            minted = true;
        }
        // API-06: the full URL only in the response that minted the token (shown once)
        return toResponse(config, tenant.getWebhookToken(), minted);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentSettingsResponse get() {
        PaymentGatewayConfig config = configRepository.findTopByOrderByIdAsc().orElse(null);
        return toResponse(config, currentTenant().getWebhookToken(), false);
    }

    @Override
    @Transactional
    public PaymentSettingsResponse rotateWebhookToken() {
        Tenant tenant = lockCurrentTenant();
        tenant.setWebhookToken(generateToken());
        tenantRepository.save(tenant);
        return toResponse(configRepository.findTopByOrderByIdAsc().orElse(null), tenant.getWebhookToken(), true);
    }

    private Tenant currentTenant() {
        String slug = TenantContext.getSlug();
        return tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new BadRequestException("No tenant resolved for this request"));
    }

    public static final String BUSY = "PAYMENT_SETTINGS_BUSY";

    /** The tenant row, locked (bounded wait: 503 PAYMENT_SETTINGS_BUSY) so token writes never interleave. */
    private Tenant lockCurrentTenant() {
        String slug = TenantContext.getSlug();
        return lockTimeout.run(BUSY, "Payment settings are being changed right now. Please try again.",
                        () -> tenantRepository.lockBySlug(slug))
                .orElseThrow(() -> new BadRequestException("No tenant resolved for this request"));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Characters of the token kept visible in {@code webhookUrlMasked}. */
    static final int MASK_VISIBLE = 4;
    static final String MASK = "****";

    /** @param showUrl true only for the response of the call that minted {@code token} (API-06: shown once) */
    private PaymentSettingsResponse toResponse(PaymentGatewayConfig config, String token, boolean showUrl) {
        boolean secretConfigured = config != null
                && config.getSecretKeyEncrypted() != null
                && !config.getSecretKeyEncrypted().isBlank();
        String publicKey = config != null ? config.getPublicKey() : null;
        boolean enabled = config != null && config.isEnabled();
        boolean hasToken = token != null && !token.isBlank();
        String prefix = apiBaseUrl + "/api/v1/webhook/";
        String webhookUrl = hasToken && showUrl ? prefix + token : null;
        String masked = hasToken ? prefix + MASK + token.substring(Math.max(0, token.length() - MASK_VISIBLE)) : null;
        return new PaymentSettingsResponse(secretConfigured, publicKey, enabled, webhookUrl, hasToken, masked);
    }
}
