package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.payment.request.PaymentSettingsRequest;
import com.example.perfume_budget.dto.payment.response.PaymentSettingsResponse;

public interface PaymentSettingsService {

    PaymentSettingsResponse save(PaymentSettingsRequest request);

    PaymentSettingsResponse get();
}
