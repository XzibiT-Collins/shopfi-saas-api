package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The reference is URL-encoded in the provider call and the tenant's own secret is the bearer. */
class PaystackVerifyEncodingTest {

    @Test
    void referenceIsUrlEncodedAndTenantSecretUsed() {
        RestTemplate rest = mock(RestTemplate.class);
        TenantPaymentGatewayProvider provider = mock(TenantPaymentGatewayProvider.class);
        when(provider.currentSecretOrThrow()).thenReturn("sk_test_FAKE_tenant");
        PaystackGatewayServiceImpl svc = new PaystackGatewayServiceImpl(rest, provider);
        ReflectionTestUtils.setField(svc, "paystackUrl", "https://api.paystack.test ");
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(PaystackVerifyTransactionResponse.class)))
                .thenReturn(ResponseEntity.ok(new PaystackVerifyTransactionResponse(true, "ok", null)));

        svc.verifyPaystackTransaction("ab+c=d/e?f#g");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(url.capture(), eq(HttpMethod.GET), entity.capture(), eq(PaystackVerifyTransactionResponse.class));
        assertEquals("https://api.paystack.test/transaction/verify/ab%2Bc%3Dd%2Fe%3Ff%23g", url.getValue());
        assertEquals("Bearer sk_test_FAKE_tenant", entity.getValue().getHeaders().getFirst("Authorization"));
    }
}
