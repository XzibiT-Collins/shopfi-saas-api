package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.exception.PaymentException;
import com.example.perfume_budget.service.interfaces.PaymentGatewayAPIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackGatewayServiceImpl implements PaymentGatewayAPIService {
    private final RestTemplate restTemplate;
    private final TenantPaymentGatewayProvider gatewayProvider;

    // Paystack base URL is the same for all tenants; only the secret key is per-tenant.
    @Value("${paystack.url}")
    private String paystackUrl;

    private static final String INITIALIZE_ENDPOINT = "/transaction/initialize";
    private static final String VERIFY_ENDPOINT = "/transaction/verify/";

    @Override
    public PaystackInitiateTransactionResponse initiatePaystackTransaction(PaystackInitiatePaymentRequest paystackRequest) {
        try {
            HttpEntity<Object> httpEntity = paystackRequestObject(paystackRequest);
            ResponseEntity<PaystackInitiateTransactionResponse> response = restTemplate.postForEntity(
                    paystackUrl.trim() + INITIALIZE_ENDPOINT,
                    httpEntity,
                    PaystackInitiateTransactionResponse.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Paystack returned unsuccessful response: {}", response.getStatusCode());
                throw new PaymentException("Payment provider returned an unsuccessful response");
            }

            if (!response.getBody().status()) {
                log.error("Paystack transaction initialization failed: {}", response.getBody().message());
                throw new PaymentException("Payment initialization failed: " + response.getBody().message());
            }

            log.info("Paystack transaction initialized successfully");
//            log.info("Response: {}, Body: {}", response, response.getBody());
            return response.getBody();

        } catch (HttpClientErrorException e) {
            // 4xx errors - bad request, unauthorized, etc.
            log.error("Paystack client error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            handleHttpClientError(e);
        } catch (HttpServerErrorException e) {
            // 5xx errors - paystack server issues
            log.error("Paystack server error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new PaymentException("Payment provider is currently unavailable, please try again later");
        } catch (ResourceAccessException e) {
            // network issues, timeouts
            log.error("Could not reach Paystack: {}", e.getMessage());
            throw new PaymentException("Could not reach payment provider, please check your connection and try again");
        } catch (RestClientException e) {
            // any other REST errors
            log.error("Unexpected error communicating with Paystack: {}", e.getMessage());
            throw new PaymentException("An unexpected error occurred while processing your payment");
        }
        throw new PaymentException("Payment initialization failed due to an unexpected error");
    }

    @Override
    public PaystackVerifyTransactionResponse verifyPaystackTransaction(String reference) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + gatewayProvider.currentSecretOrThrow());
            ResponseEntity<PaystackVerifyTransactionResponse> response = restTemplate.exchange(
                    paystackUrl.trim() + VERIFY_ENDPOINT + java.net.URLEncoder.encode(reference, java.nio.charset.StandardCharsets.UTF_8),
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers),
                    PaystackVerifyTransactionResponse.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PaymentException("Payment provider returned an unsuccessful response");
            }
            return response.getBody();
        } catch (RestClientException e) {
            // Class name only: the exception text may echo request details.
            log.warn("Paystack verification failed: {}", e.getClass().getSimpleName());
            throw new PaymentException("Could not verify the payment with the provider");
        }
    }

    private void handleHttpClientError(HttpClientErrorException e) {
        switch (e.getStatusCode().value()) {
            case 400 -> throw new PaymentException("Invalid payment request: " + e.getResponseBodyAsString());
            case 401 -> throw new PaymentException("Payment provider authentication failed");
            case 404 -> throw new PaymentException("Payment provider endpoint not found");
            case 429 -> throw new PaymentException("Too many payment requests, please try again later");
            default -> throw new PaymentException("Payment request failed: " + e.getResponseBodyAsString());
        }
    }

    private HttpEntity<Object> paystackRequestObject(Object request) {
        HttpHeaders headers = new HttpHeaders();
        // Per-tenant secret resolved from the tenant's own gateway config (active TenantContext).
        headers.set("Authorization", "Bearer " + gatewayProvider.currentSecretOrThrow());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(request, headers);
    }
}
