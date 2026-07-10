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
        }
        config.setPublicKey(request.publicKey().strip());
        config.setEnabled(request.enabled());
        config = configRepository.save(config);

        String token = ensureWebhookToken();
        return toResponse(config, token);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentSettingsResponse get() {
        PaymentGatewayConfig config = configRepository.findTopByOrderByIdAsc().orElse(null);
        return toResponse(config, currentTenant().getWebhookToken());
    }

    private String ensureWebhookToken() {
        Tenant tenant = currentTenant();
        if (tenant.getWebhookToken() == null || tenant.getWebhookToken().isBlank()) {
            tenant.setWebhookToken(generateToken());
            tenantRepository.save(tenant);
        }
        return tenant.getWebhookToken();
    }

    private Tenant currentTenant() {
        String slug = TenantContext.getSlug();
        return tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new BadRequestException("No tenant resolved for this request"));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private PaymentSettingsResponse toResponse(PaymentGatewayConfig config, String token) {
        boolean secretConfigured = config != null
                && config.getSecretKeyEncrypted() != null
                && !config.getSecretKeyEncrypted().isBlank();
        String publicKey = config != null ? config.getPublicKey() : null;
        boolean enabled = config != null && config.isEnabled();
        String webhookUrl = token != null ? apiBaseUrl + "/api/v1/webhook/" + token : null;
        return new PaymentSettingsResponse(secretConfigured, publicKey, enabled, webhookUrl);
    }
}
