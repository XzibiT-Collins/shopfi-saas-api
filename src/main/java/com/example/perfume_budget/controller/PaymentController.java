package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.checkout.PaymentVerificationResponse;
import com.example.perfume_budget.service.PaymentVerificationService;
import com.example.perfume_budget.utils.AuthUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentVerificationService verificationService;
    private final AuthUserUtil authUserUtil;

    /**
     * State of the caller's own payment by Paystack reference (the value Paystack puts in the callback URL).
     * The reference is only a lookup key: it can never change an order's state from the client side.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/verify")
    public ResponseEntity<CustomApiResponse<PaymentVerificationResponse>> verify(
            @RequestParam(name = "reference") String reference) {
        return ResponseEntity.ok().body(CustomApiResponse.success(
                verificationService.verify(reference, authUserUtil.getCurrentUser())));
    }
}
