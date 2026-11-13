package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.payment.request.PaymentSettingsRequest;
import com.example.perfume_budget.dto.payment.response.PaymentSettingsResponse;
import com.example.perfume_budget.service.interfaces.PaymentSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Tenant-admin management of the tenant's own Paystack credentials. The response includes the
 * per-tenant webhook URL to paste into that tenant's Paystack dashboard.
 */
@RestController
@RequestMapping("/api/v1/payment-settings")
@RequiredArgsConstructor
public class PaymentSettingsController {

    private final PaymentSettingsService paymentSettingsService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CustomApiResponse<PaymentSettingsResponse>> get() {
        return ResponseEntity.ok(CustomApiResponse.success(paymentSettingsService.get()));
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CustomApiResponse<PaymentSettingsResponse>> update(@RequestBody @Valid PaymentSettingsRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(paymentSettingsService.save(request)));
    }
}
