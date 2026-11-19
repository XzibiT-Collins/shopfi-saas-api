package com.example.perfume_budget.checkout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * FIX-B (ORD-07 review lows): a returned order is out of EVERY coupon metric on its own (the list and the per-coupon
 * query, with a kept order next to it); the customer gets exactly one RETURNED e-mail (none for a repeat, none for a
 * refused return) and it produces exactly one INFO line in the log.
 */
class OrderReturnFollowupsIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired OrderRepository orderRepository;

    private String adminToken;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void setUp() throws Exception {
        for (String s : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.update("UPDATE " + s + ".ledger_accounts SET balance = balance + 100000 WHERE category IN ('INVENTORY', 'CASH')");
        }
        adminToken = setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void detach() {
        root.detachAppender(logs);
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.header("X-Tenant-ID", "acme").cookie(cookie("accessToken", adminToken));
    }

    private record Placed(Customer c, String orderNumber) {}

    private Placed placePaid(String email, String couponQuery) throws Exception {
        long p = product(acme, "Ret " + email, "10.00", 10);
        jdbc.update("UPDATE tenant_acme.products SET cost_price_amount = 4.00, cost_price_currency = 'GHS' WHERE id = ?", p);
        Customer c = customer(acme, email);
        cart(c, p, 2);
        MvcResult r = checkout(c, null, couponQuery);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        String on = d.get("orderNumber").asText();
        String ref = d.at("/data/reference").asText();
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, on).longValue();
        verifyAnswer = new PaystackVerifyTransactionResponse(true, "ok",
                new PaystackVerifyTransactionResponse.Data("success", ref, BigDecimal.valueOf(minor), "GHS", "card", "Successful"));
        verify(c, ref);
        verifyAnswer = null;
        assertThat(orderStatus(acme, on)).isEqualTo("COMPLETED");
        return new Placed(c, on);
    }

    private int setStatus(String on, String status) throws Exception {
        return mvc.perform(admin(put("/api/v1/order/update-order-status/" + on).contentType("application/json")
                .content("{\"orderStatus\":\"" + status + "\"}"))).andReturn().getResponse().getStatus();
    }

    private int returnOrder(String on) throws Exception {
        return mvc.perform(admin(post("/api/v1/order/" + on + "/return"))).andReturn().getResponse().getStatus();
    }

    private List<MailMessage> statusMails(String to) {
        return Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (MailMessage) i.getArgument(0))
                .filter(m -> to.equals(m.to()) && "Order Status Update".equals(m.subject()))
                .toList();
    }

    @Test
    void aReturnedOrderIsOutOfEveryCouponMetric_whileTheKeptOrderStaysIn() throws Exception {
        coupon(acme, "RETM10", 10);
        long couponId = jdbc.queryForObject("SELECT id FROM tenant_acme.coupons WHERE code = 'RETM10'", Long.class);
        Placed kept = placePaid("retm-kept@acme.test", "couponCode=RETM10");
        Placed returned = placePaid("retm-ret@acme.test", "couponCode=RETM10");
        BigDecimal keptTotal = jdbc.queryForObject("SELECT total_amount FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, kept.orderNumber());
        BigDecimal keptDiscount = jdbc.queryForObject("SELECT discount_amount FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, kept.orderNumber());
        assertThat(setStatus(kept.orderNumber(), "DELIVERED")).isEqualTo(200);
        assertThat(setStatus(returned.orderNumber(), "DELIVERED")).isEqualTo(200);

        tenants.runAs(acme, () -> {
            assertThat(orderRepository.getCouponMetrics(couponId, PaymentStatus.COMPLETED).getTotalUsages()).as("before: both").isEqualTo(2L);
            return null;
        });
        assertThat(returnOrder(returned.orderNumber())).isEqualTo(200);

        tenants.runAs(acme, () -> {
            var one = orderRepository.getCouponMetrics(couponId, PaymentStatus.COMPLETED);
            assertThat(one.getTotalUsages()).as("per-coupon usages").isEqualTo(1L);
            assertThat(one.getRevenueGenerated()).as("per-coupon revenue").isEqualByComparingTo(keptTotal);
            assertThat(one.getTotalDiscountGiven()).as("per-coupon discount").isEqualByComparingTo(keptDiscount);
            var row = orderRepository.getAllCouponMetrics(PaymentStatus.COMPLETED).stream()
                    .filter(m -> "RETM10".equals(m.getCouponCode())).findFirst().orElseThrow();
            assertThat(row.getTotalUsages()).as("coupon list usages").isEqualTo(1L);
            assertThat(row.getRevenueGenerated()).as("coupon list revenue").isEqualByComparingTo(keptTotal);
            assertThat(row.getTotalDiscountGiven()).as("coupon list discount").isEqualByComparingTo(keptDiscount);
            return null;
        });
        // the redemption itself went back (ORD-02b rule), the kept one still holds its slot
        assertThat(jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code = 'RETM10'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theCustomerGetsExactlyOneReturnedEmail_noneForARepeatOrARefusedReturn_andOneLogLine() throws Exception {
        Placed o = placePaid("retmail@acme.test", null);
        Placed notDelivered = placePaid("retmail2@acme.test", null);
        assertThat(setStatus(o.orderNumber(), "DELIVERED")).isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(statusMails("retmail@acme.test")).as("the DELIVERED mail").hasSize(1));
        logs.list.clear();

        assertThat(returnOrder(o.orderNumber())).isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(statusMails("retmail@acme.test")).as("DELIVERED + RETURNED").hasSize(2));
        MailMessage returned = statusMails("retmail@acme.test").get(1);
        assertThat(returned.html()).contains("Order Returned").contains(o.orderNumber()).doesNotContain("Order Delivered");

        // exactly one INFO line about this e-mail (the sent line): no second "about to send" line
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(emailInfoLines(o.orderNumber())).isNotEmpty());
        assertThat(emailInfoLines(o.orderNumber())).as("INFO lines about the RETURNED e-mail").hasSize(1);

        // a repeat is a 200 no-op and sends nothing; a return of an order that was not delivered is a 409 and sends nothing
        assertThat(returnOrder(o.orderNumber())).isEqualTo(200);
        assertThat(returnOrder(notDelivered.orderNumber())).isEqualTo(409);
        await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            assertThat(statusMails("retmail@acme.test")).hasSize(2);
            assertThat(statusMails("retmail2@acme.test")).isEmpty();
        });
    }

    private List<String> emailInfoLines(String orderNumber) {
        return new java.util.ArrayList<>(logs.list).stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(orderNumber) && m.toLowerCase().contains("e-mail"))
                .toList();
    }
}
