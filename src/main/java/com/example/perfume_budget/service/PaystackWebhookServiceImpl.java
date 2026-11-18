package com.example.perfume_budget.service;

import com.example.perfume_budget.service.interfaces.WebhookProcessor;
import com.example.perfume_budget.service.interfaces.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackWebhookServiceImpl implements WebhookService {
    private final WebhookProcessor webhookProcessor;
    private final TenantPaymentGatewayProvider gatewayProvider;

    @Override
    public ResponseEntity<String> checkWebhookSignature(String body, String signature) {
        try {
            log.info("Received webhook from Paystack.");

            // Verify with THIS tenant's secret (tenant already set on the thread by the controller).
            String computedHash = hmacSha512(body, gatewayProvider.currentSecretOrThrow());
            if (signature == null || !MessageDigest.isEqual(
                    computedHash.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Invalid Paystack signature");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
            }
            log.info("Webhook signature verified");

            // EVT-01c: applied synchronously; 200 only once it committed (or could never be applied), so a failure
            // (WebhookNotAppliedException) answers 500 below and Paystack redelivers the event; all settlement
            // permits taken (WebhookBusyException) answers 503, also redelivered
            webhookProcessor.processWebhook(body);

            return ResponseEntity.ok("Processed");
        } catch (WebhookSettlementBulkhead.WebhookBusyException e) {
            // EVT-01c rework 1: every settlement permit is taken; nothing was applied and no connection was held.
            // 503 so Paystack redelivers later; generic body, no internals.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Busy");
        } catch (Exception e) {
            log.error("Error processing webhook: {}", e.getClass().getName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }

    // HELPER METHODS
    private String hmacSha512(String data, String secret) throws Exception {
        Mac sha512Hmac = Mac.getInstance("HmacSHA512");
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        sha512Hmac.init(secretKey);
        byte[] hashBytes = sha512Hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
