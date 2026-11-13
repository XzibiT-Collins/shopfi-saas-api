package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.PaymentException;
import com.example.perfume_budget.model.PaymentGatewayConfig;
import com.example.perfume_budget.repository.PaymentGatewayConfigRepository;
import com.example.perfume_budget.utils.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves the current tenant's payment gateway credentials (from its schema, under the active
 * {@code TenantContext}) and decrypts the secret on demand. Used at checkout (init) and at
 * webhook verification.
 */
@Service
@RequiredArgsConstructor
public class TenantPaymentGatewayProvider {

    private final PaymentGatewayConfigRepository configRepository;
    private final AesGcmEncryptor encryptor;

    @Transactional(readOnly = true)
    public String currentSecretOrThrow() {
        PaymentGatewayConfig config = configRepository.findTopByOrderByIdAsc()
                .orElseThrow(() -> new PaymentException("Payment is not configured for this store"));
        if (!config.isEnabled() || config.getSecretKeyEncrypted() == null
                || config.getSecretKeyEncrypted().isBlank()) {
            throw new PaymentException("Payment is not enabled for this store");
        }
        return encryptor.decrypt(config.getSecretKeyEncrypted());
    }

    @Transactional(readOnly = true)
    public Optional<PaymentGatewayConfig> currentConfig() {
        return configRepository.findTopByOrderByIdAsc();
    }
}
