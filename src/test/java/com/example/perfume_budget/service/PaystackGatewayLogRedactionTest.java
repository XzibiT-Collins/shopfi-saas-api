package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.exception.PaymentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-01d (3): a Paystack error body can carry the customer's e-mail, authorization / access codes and card metadata.
 * The gateway logs only the HTTP status, Paystack's own {@code status} / {@code message} (redacted) / {@code code},
 * and our order reference; never the raw body. The PaymentException text (stored as the payment's failure reason
 * and answered to the client) never carries the body either.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaystackGatewayLogRedactionTest {

    static final String EMAIL = "shopper-leak@x.test";
    static final String ORDER = "ORD-20261118-ABC123";
    /** A Paystack-shaped error body with personal data and secrets in places other than "message". */
    static final String BODY = "{\"status\":false,\"message\":\"Invalid Email Address Passed: " + EMAIL + "\","
            + "\"type\":\"validation_error\",\"code\":\"invalid_params\","
            + "\"data\":{\"customer\":{\"email\":\"" + EMAIL + "\"},\"access_code\":\"acc_LEAK0123456\","
            + "\"authorization\":{\"authorization_code\":\"AUTH_leak987\",\"last4\":\"4081\",\"bin\":\"408408\"}}}";

    private RestTemplate rest;
    private PaystackGatewayServiceImpl svc;

    @BeforeEach
    void setUp() {
        rest = mock(RestTemplate.class);
        TenantPaymentGatewayProvider provider = mock(TenantPaymentGatewayProvider.class);
        when(provider.currentSecretOrThrow()).thenReturn("sk_test_FAKE_tenant_secret");
        svc = new PaystackGatewayServiceImpl(rest, provider);
        ReflectionTestUtils.setField(svc, "paystackUrl", "https://api.paystack.test");
    }

    private static PaystackInitiatePaymentRequest request() {
        return PaystackInitiatePaymentRequest.builder().email(EMAIL).amount(new BigDecimal("1000"))
                .currency(CurrencyCode.GHS).orderReference(ORDER).build();
    }

    private static void assertNoRawBody(String text) {
        assertThat(text).doesNotContain(EMAIL).doesNotContain("acc_LEAK").doesNotContain("AUTH_leak")
                .doesNotContain("4081").doesNotContain("408408").doesNotContain("sk_test_FAKE").doesNotContain("{\"status\"");
    }

    private void failWith(RuntimeException e) {
        when(rest.postForEntity(anyString(), any(HttpEntity.class), eq(PaystackInitiateTransactionResponse.class))).thenThrow(e);
    }

    @Test
    void a4xxLogsStatusPaystackFieldsAndOrderReference_neverTheBody(CapturedOutput output) {
        failWith(HttpClientErrorException.create(org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request",
                new HttpHeaders(), BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThatThrownBy(() -> svc.initiatePaystackTransaction(request()))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertNoRawBody(e.getMessage()))
                .hasMessage("Invalid payment request");
        String logged = output.getAll();
        assertThat(logged).contains("400").contains(ORDER).contains("status=false")
                .contains("Invalid Email Address Passed: [redacted]").contains("code=invalid_params");
        assertNoRawBody(logged);
    }

    @Test
    void anOther4xxHasAFixedExceptionText(CapturedOutput output) {
        failWith(HttpClientErrorException.create(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "x",
                new HttpHeaders(), BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThatThrownBy(() -> svc.initiatePaystackTransaction(request())).hasMessage("Payment request failed");
        assertNoRawBody(output.getAll());
    }

    @Test
    void a5xxLogsStatusAndOrderReference_neverTheBody(CapturedOutput output) {
        failWith(HttpServerErrorException.create(org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway",
                new HttpHeaders(), BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThatThrownBy(() -> svc.initiatePaystackTransaction(request())).isInstanceOf(PaymentException.class);
        String logged = output.getAll();
        assertThat(logged).contains("502").contains(ORDER);
        assertNoRawBody(logged);
    }

    @Test
    void aBodyThatIsNotPaystackJsonIsNotLoggedAtAll(CapturedOutput output) {
        String html = "<html>gateway said " + EMAIL + " acc_LEAK0123456</html>";
        failWith(HttpClientErrorException.create(org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request",
                new HttpHeaders(), html.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThatThrownBy(() -> svc.initiatePaystackTransaction(request())).isInstanceOf(PaymentException.class);
        assertThat(output.getAll()).contains("400").contains(ORDER);
        assertNoRawBody(output.getAll());
    }

    @Test
    void aStatusFalseAnswerLogsTheRedactedMessage_andAnswersAFixedText(CapturedOutput output) {
        when(rest.postForEntity(anyString(), any(HttpEntity.class), eq(PaystackInitiateTransactionResponse.class)))
                .thenReturn(ResponseEntity.ok(new PaystackInitiateTransactionResponse(false,
                        "Customer " + EMAIL + " card 4084084084084081 declined\nFAKE LOG LINE",
                        new PaystackInitiateTransactionResponse.Data("https://checkout.paystack.com/acc_LEAK0123456",
                                "acc_LEAK0123456", "ref1"))));
        assertThatThrownBy(() -> svc.initiatePaystackTransaction(request()))
                .hasMessage("Payment initialization failed");
        String logged = output.getAll();
        assertThat(logged).contains(ORDER).contains("Customer [redacted] card [redacted] declined FAKE LOG LINE");
        assertNoRawBody(logged);
    }

    @Test
    void theOrderReferenceIsNeverSentToPaystack() throws Exception {
        String sent = new ObjectMapper().writeValueAsString(request());
        assertThat(sent).doesNotContain(ORDER).doesNotContain("orderReference");
    }
}
