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

            // Publish to async queue for further processing
            webhookProcessor.processWebhook(body);

            return ResponseEntity.ok("Processed");
        } catch (Exception e) {
            log.error("Error processing webhook: {}", e.getMessage());
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
