package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
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

    @Value("${paystack.secret}")
    private String paystackSecret;

    @Value("${paystack.url}")
    private String paystackUrl;

    private static final String INITIALIZE_ENDPOINT = "/transaction/initialize";

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
        headers.set("Authorization", "Bearer " + paystackSecret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(request, headers);
    }
}
