package com.example.perfume_budget.controller;

import com.example.perfume_budget.service.interfaces.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {
    private final WebhookService webhookService;

    @PostMapping
    public ResponseEntity<String> processWebhook(
            @RequestBody String rawBody,
            @RequestHeader("X-Paystack-Signature") String signature){

        return webhookService.checkWebhookSignature(rawBody, signature);
    }
}
