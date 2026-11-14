package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;

public interface PaymentGatewayAPIService {
    PaystackInitiateTransactionResponse initiatePaystackTransaction(PaystackInitiatePaymentRequest paystackRequest);

    /**
     * Asks Paystack for the authoritative state of a transaction, using the CURRENT tenant's own secret key.
     * Read only. Throws PaymentException when the provider cannot be reached or answers with an error.
     *
     * @param reference an already validated reference (charset/length checked by the caller)
     */
    PaystackVerifyTransactionResponse verifyPaystackTransaction(String reference);
}
