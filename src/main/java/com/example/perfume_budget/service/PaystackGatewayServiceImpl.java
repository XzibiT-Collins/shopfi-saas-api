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
        String order = paystackRequest.orderReference();
        try {
            HttpEntity<Object> httpEntity = paystackRequestObject(paystackRequest);
            ResponseEntity<PaystackInitiateTransactionResponse> response = restTemplate.postForEntity(
                    paystackUrl.trim() + INITIALIZE_ENDPOINT,
                    httpEntity,
                    PaystackInitiateTransactionResponse.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Paystack returned unsuccessful response {} for order {}", response.getStatusCode().value(), order);
                throw new PaymentException("Payment provider returned an unsuccessful response");
            }

            if (!response.getBody().status()) {
                // SEC-01d: Paystack's message only, redacted; the client and the stored failure reason get fixed text
                log.error("Paystack transaction initialization failed for order {}: status=false message={}", order,
                        PaystackErrorSummary.redact(response.getBody().message()));
                throw new PaymentException("Payment initialization failed");
            }

            log.info("Paystack transaction initialized successfully");
            return response.getBody();

        } catch (HttpClientErrorException e) {
            // 4xx errors - bad request, unauthorized, etc. SEC-01d: never the raw body (it can carry the customer's
            // e-mail, authorization / access codes and card metadata): status code, Paystack's own fields, our order
            log.error("Paystack client error {} for order {}: {}", e.getStatusCode().value(), order, PaystackErrorSummary.of(e));
            handleHttpClientError(e);
        } catch (HttpServerErrorException e) {
            // 5xx errors - paystack server issues
            log.error("Paystack server error {} for order {}: {}", e.getStatusCode().value(), order, PaystackErrorSummary.of(e));
            throw new PaymentException("Payment provider is currently unavailable, please try again later");
        } catch (ResourceAccessException e) {
            // network issues, timeouts
            log.error("Could not reach Paystack for order {}: {}", order, e.getClass().getName());
            throw new PaymentException("Could not reach payment provider, please check your connection and try again");
        } catch (RestClientException e) {
            // any other REST errors
            log.error("Unexpected error communicating with Paystack for order {}: {}", order, e.getClass().getName());
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
            // SEC-01d: fixed texts: this message is answered to the client and stored as the payment's failure reason
            case 400 -> throw new PaymentException("Invalid payment request");
            case 401 -> throw new PaymentException("Payment provider authentication failed");
            case 404 -> throw new PaymentException("Payment provider endpoint not found");
            case 429 -> throw new PaymentException("Too many payment requests, please try again later");
            default -> throw new PaymentException("Payment request failed");
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
