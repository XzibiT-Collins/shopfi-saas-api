package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.checkout.PaymentVerificationResponse;
import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.PaymentException;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.service.interfaces.PaymentGatewayAPIService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * GET /api/v1/payment/verify?reference=. The only client input is the reference, used purely as a lookup key inside
 * the caller's own orders of the current tenant. The client can never mark anything paid: an unsettled payment is
 * (at most once per interval) re-checked with Paystack server side using the tenant's own secret key, the answer is
 * applied through {@link PaymentSettlementService} (same idempotent, amount-checked path as the webhook), and the
 * stored state is returned. If the provider cannot be reached the current (pending) state is returned honestly.
 */
@Slf4j
@Service
public class PaymentVerificationService {

    /** Same shape the storefront validates before using the value (docs/api/checkout.md). */
    static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9._=-]{6,100}$");
    static final String NOT_FOUND = "Payment not found.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayAPIService gatewayService;
    private final PaymentSettlementService settlementService;
    private final TransactionTemplate tx;
    private final long minIntervalSeconds;

    public PaymentVerificationService(PaymentRepository paymentRepository,
                                      PaymentGatewayAPIService gatewayService,
                                      PaymentSettlementService settlementService,
                                      PlatformTransactionManager txManager,
                                      @Value("${payment.verify.min-interval-seconds:5}") long minIntervalSeconds) {
        this.paymentRepository = paymentRepository;
        this.gatewayService = gatewayService;
        this.settlementService = settlementService;
        this.tx = new TransactionTemplate(txManager);
        this.minIntervalSeconds = Math.max(0, minIntervalSeconds);
    }

    public PaymentVerificationResponse verify(String reference, User owner) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Invalid payment reference.");
        }
        Payment payment = findOwned(reference, owner);
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            checkWithProvider(payment, reference);
            payment = findOwned(reference, owner);
        }
        return toResponse(payment, reference);
    }

    private Payment findOwned(String reference, User owner) {
        return paymentRepository.findOwnedByProviderReference(reference, owner)
                .orElseThrow(() -> new ApiCodeException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", NOT_FOUND));
    }

    private void checkWithProvider(Payment payment, String reference) {
        LocalDateTime now = LocalDateTime.now();
        Integer claimed = tx.execute(s ->
                paymentRepository.claimProviderCheck(payment.getId(), now, now.minusSeconds(minIntervalSeconds)));
        if (claimed == null || claimed == 0) {
            return; // someone (another poll, a webhook) checked very recently: report stored state
        }
        PaystackVerifyTransactionResponse response;
        try {
            response = gatewayService.verifyPaystackTransaction(reference);
        } catch (PaymentException e) {
            log.info("Provider verification unavailable for order {}: reporting stored state", payment.getOrder().getOrderNumber());
            return;
        }
        PaystackVerifyTransactionResponse.Data data = response == null ? null : response.data();
        if (data == null || !response.status() || !reference.equals(data.reference())) {
            return;
        }
        String json = toJson(data);
        String providerStatus = data.status() == null ? "" : data.status().toLowerCase();
        switch (providerStatus) {
            case "success" -> settlementService.applySuccess(reference, data.channel(), data.amount(), data.currency(), json);
            case "failed" -> settlementService.applyFailure(reference, data.channel(), data.gatewayResponse(), json);
            default -> { /* pending / abandoned / ongoing: nothing is final yet */ }
        }
    }

    private static String toJson(PaystackVerifyTransactionResponse.Data data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static PaymentVerificationResponse toResponse(Payment payment, String reference) {
        Order order = payment.getOrder();
        PaymentStatus status = order.getStatus();
        return PaymentVerificationResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .reference(reference)
                .paymentStatus(status)
                .settled(status == PaymentStatus.COMPLETED || status == PaymentStatus.FAILED || status == PaymentStatus.CANCELLED)
                .refundRequired(payment.isRefundRequired())
                .build();
    }
}
