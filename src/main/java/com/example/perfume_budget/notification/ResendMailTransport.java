package com.example.perfume_budget.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
public class ResendMailTransport implements MailTransport {
    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String baseUrl;
    private final String fromAddress;
    private final String fromName;

    public ResendMailTransport(
            RestTemplate restTemplate,
            String apiKey,
            String baseUrl,
            String fromAddress,
            String fromName
    ) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.fromAddress = fromAddress;
        this.fromName = fromName;

        Assert.hasText(this.apiKey, "notification.email.resend.api-key must be configured when Resend is enabled");
        Assert.hasText(this.fromAddress, "notification.email.from-address must be configured");
    }

    @Override
    public void send(MailMessage message) {
        String endpoint = buildEndpoint();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ResendSendEmailRequest request = new ResendSendEmailRequest(
                formatSender(),
                List.of(message.to()),
                message.subject(),
                message.html()
        );

        ResponseEntity<ResendSendEmailResponse> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                ResendSendEmailResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new MailDeliveryException("Failed to send email via Resend");
        }
    }

    private String buildEndpoint() {
        String normalizedBaseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.resend.com"
                : baseUrl.strip();
        if (normalizedBaseUrl.endsWith("/")) {
            return normalizedBaseUrl + "emails";
        }
        return normalizedBaseUrl + "/emails";
    }

    private String formatSender() {
        if (fromName == null || fromName.isBlank()) {
            return fromAddress;
        }
        return fromName.strip() + " <" + fromAddress.strip() + ">";
    }

    private record ResendSendEmailRequest(
            String from,
            List<String> to,
            String subject,
            String html
    ) {
    }

    private record ResendSendEmailResponse(String id) {
    }
}
