package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;

public interface PaymentGatewayAPIService {
    PaystackInitiateTransactionResponse initiatePaystackTransaction(PaystackInitiatePaymentRequest paystackRequest);
}
