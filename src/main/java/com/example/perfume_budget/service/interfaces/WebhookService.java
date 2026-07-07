package com.example.perfume_budget.service.interfaces;

import org.springframework.http.ResponseEntity;

public interface WebhookService {
    ResponseEntity<String> checkWebhookSignature(String body, String signature);
}
