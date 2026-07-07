package com.example.perfume_budget.service;

import com.example.perfume_budget.service.interfaces.WebhookProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaystackWebhookServiceImplTest {

    @Mock
    private WebhookProcessor webhookProcessor;

    @InjectMocks
    private PaystackWebhookServiceImpl webhookService;

    private final String secret = "test-secret";
    private final String body = "{\"event\":\"charge.success\"}";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(webhookService, "paystackSecret", secret);
    }

    @Test
    void checkWebhookSignature_Success() throws Exception {
        String signature = hmacSha512(body, secret);

        ResponseEntity<String> response = webhookService.checkWebhookSignature(body, signature);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookProcessor).processWebhook(body);
    }

    @Test
    void checkWebhookSignature_Failure_InvalidSignature() {
        ResponseEntity<String> response = webhookService.checkWebhookSignature(body, "invalid-sig");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(webhookProcessor, never()).processWebhook(anyString());
    }

    // Helper to generate signature for testing
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
