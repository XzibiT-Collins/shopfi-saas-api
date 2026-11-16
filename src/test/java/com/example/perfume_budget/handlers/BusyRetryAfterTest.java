package com.example.perfume_budget.handlers;

import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import com.example.perfume_budget.service.PaymentSettingsServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** EVT-01 (d) / API-04 N11: {@code 503 PAYMENT_SETTINGS_BUSY} carries {@code Retry-After: 1}; other codes do not. */
class BusyRetryAfterTest {

    @Test
    void paymentSettingsBusyIs503WithRetryAfter() {
        ResponseEntity<?> r = new GlobalExceptionHandler().handleApiCodeException(new ApiCodeException(
                HttpStatus.SERVICE_UNAVAILABLE, PaymentSettingsServiceImpl.BUSY, "Payment settings are being changed"));
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void aNonBusyCodeHasNoRetryAfter() {
        ResponseEntity<?> r = new GlobalExceptionHandler().handleApiCodeException(new ApiCodeException(
                HttpStatus.BAD_REQUEST, "INVALID_SORT", "Unsupported sort property."));
        assertThat(r.getHeaders().containsKey("Retry-After")).isFalse();
    }
}
