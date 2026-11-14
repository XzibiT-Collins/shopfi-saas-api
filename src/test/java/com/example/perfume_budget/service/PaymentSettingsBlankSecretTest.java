package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaymentSettingsRequest;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.PaymentGatewayConfig;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.repository.PaymentGatewayConfigRepository;
import com.example.perfume_budget.utils.AesGcmEncryptor;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** TENANT-09: blank secret keeps the stored one; first-time save without a secret is rejected. */
class PaymentSettingsBlankSecretTest {

    private final PaymentGatewayConfigRepository repo = mock(PaymentGatewayConfigRepository.class);
    private final AesGcmEncryptor encryptor = mock(AesGcmEncryptor.class);
    private final PaymentSettingsServiceImpl service =
            new PaymentSettingsServiceImpl(repo, mock(TenantRepository.class), encryptor);

    @Test
    void firstTimeSaveWithBlankSecretIsRejected() {
        when(repo.findTopByOrderByIdAsc()).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class,
                () -> service.save(new PaymentSettingsRequest("  ", "pk_test", true)));
        assertThrows(BadRequestException.class,
                () -> service.save(new PaymentSettingsRequest(null, "pk_test", true)));
        verify(repo, never()).save(any());
    }

    @Test
    void firstTimeSaveWhereStoredSecretIsBlankIsRejected() {
        PaymentGatewayConfig existing = new PaymentGatewayConfig();
        existing.setSecretKeyEncrypted("");
        when(repo.findTopByOrderByIdAsc()).thenReturn(Optional.of(existing));
        assertThrows(BadRequestException.class,
                () -> service.save(new PaymentSettingsRequest("", "pk_test", true)));
    }
}
