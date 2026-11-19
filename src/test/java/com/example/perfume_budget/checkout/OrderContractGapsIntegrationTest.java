package com.example.perfume_budget.checkout;

import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * FIX-B (API-02, ORD-04): the order list rows say whether they can still be cancelled, the status-change body errors
 * carry a stable data.code, and the documented sort of the admin list is what the database does.
 */
class OrderContractGapsIntegrationTest extends AbstractCheckoutIntegrationTest {

    private String adminToken;
    private Customer rowOwner;

    @BeforeEach
    void login() throws Exception {
        adminToken = setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.header("X-Tenant-ID", "acme").cookie(cookie("accessToken", adminToken));
    }

    private String place(Customer c, long product) throws Exception {
        cart(c, product, 1);
        return body(checkout(c, null, null)).at("/data/orderNumber").asText();
    }

    private MvcResult putStatus(String orderNumber, String rawBody) throws Exception {
        MockHttpServletRequestBuilder b = admin(put("/api/v1/order/update-order-status/" + orderNumber));
        if (rawBody != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(rawBody);
        }
        return mvc.perform(b).andReturn();
    }

    @Test
    void listRowsCarryCancellable_trueOnlyWhilePaymentIsPendingOrInitiated() throws Exception {
        long p = product(acme, "CancelRow", "10.00", 20);
        Customer c = customer(acme, "cr1@acme.test");
        rowOwner = c;
        String on = place(c, p);

        JsonNode mine = body(mvc.perform(as(get("/api/v1/order/my-orders"), c)).andReturn()).at("/data/content/0");
        assertThat(mine.get("orderNumber").asText()).isEqualTo(on);
        assertThat(mine.get("cancellable").isBoolean()).isTrue();
        assertThat(mine.get("cancellable").asBoolean()).as("INITIATED order").isTrue();
        assertThat(mine.get("paymentStatus").asText()).isEqualTo("INITIATED");

        for (String status : List.of("PENDING")) {
            jdbc.update("UPDATE tenant_acme.orders SET status = ? WHERE order_number = ?", status, on);
            assertThat(listRow(on, true).get("cancellable").asBoolean()).as(status).isTrue();
        }
        for (String status : List.of("COMPLETED", "FAILED", "CANCELLED")) {
            jdbc.update("UPDATE tenant_acme.orders SET status = ? WHERE order_number = ?", status, on);
            assertThat(listRow(on, true).get("cancellable").asBoolean()).as(status + " (customer list)").isFalse();
            assertThat(listRow(on, false).get("cancellable").asBoolean()).as(status + " (admin list)").isFalse();
        }
        jdbc.update("UPDATE tenant_acme.orders SET status = 'INITIATED' WHERE order_number = ?", on);
        assertThat(listRow(on, false).get("cancellable").asBoolean()).as("admin list").isTrue();
    }

    private JsonNode listRow(String orderNumber, boolean customerList) throws Exception {
        Customer c = rowOwner;
        MvcResult r = customerList
                ? mvc.perform(as(get("/api/v1/order/my-orders"), c)).andReturn()
                : mvc.perform(admin(get("/api/v1/order/all").param("q", orderNumber))).andReturn();
        for (JsonNode row : body(r).at("/data/content")) {
            if (row.get("orderNumber").asText().equals(orderNumber)) {
                return row;
            }
        }
        return org.junit.jupiter.api.Assertions.fail("row not listed: " + r.getResponse().getContentAsString());
    }

    @Test
    void statusChangeBodyErrorsCarryAStableDataCode_andNeverEchoWhatWasSent() throws Exception {
        long p = product(acme, "BodyCodes", "10.00", 20);
        String on = place(customer(acme, "bc1@acme.test"), p);

        String[][] cases = {
                {"{}", "INVALID_ORDER_STATUS"},
                {"{\"orderStatus\":null}", "INVALID_ORDER_STATUS"},
                {"{\"orderStatus\":\"NOT_A_STATUS_9Z\"}", "INVALID_ORDER_STATUS"},
                {"{\"orderStatus\":\"packing\"}", "INVALID_ORDER_STATUS"},
                {"{\"orderStatus\":", "INVALID_REQUEST_BODY"},
                {"not json", "INVALID_REQUEST_BODY"},
                {"{\"orderStatus\":\"PACKING\",\"version\":\"abc\"}", "INVALID_REQUEST_BODY"},
                {null, "INVALID_REQUEST_BODY"},
        };
        for (String[] c : cases) {
            MvcResult r = putStatus(on, c[0]);
            assertThat(r.getResponse().getStatus()).as(String.valueOf(c[0])).isEqualTo(400);
            JsonNode json = body(r);
            assertThat(json.at("/data/code").asText()).as(String.valueOf(c[0]) + " -> " + r.getResponse().getContentAsString())
                    .isEqualTo(c[1]);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("NOT_A_STATUS_9Z").doesNotContain("abc");
            if (c[1].equals("INVALID_ORDER_STATUS")) {
                List<String> known = new ArrayList<>();
                json.at("/data/knownValues").forEach(n -> known.add(n.asText()));
                assertThat(known).containsExactly("PENDING", "PACKING", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED", "RETURNED");
            }
        }
        assertThat(orderStatus(acme, on)).as("nothing changed").isEqualTo("INITIATED");
        // a valid body still works
        assertThat(putStatus(on, "{\"orderStatus\":\"PACKING\"}").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void adminListSortByStatusIsThePaymentStatusColumnInAlphabeticalEnumNameOrder() throws Exception {
        long p = product(acme, "SortStatus", "10.00", 50);
        String[] numbers = new String[4];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = place(customer(acme, "ss" + i + "@acme.test"), p);
        }
        jdbc.update("UPDATE tenant_acme.orders SET status = 'PENDING', delivery_status = 'RETURNED' WHERE order_number = ?", numbers[0]);
        jdbc.update("UPDATE tenant_acme.orders SET status = 'COMPLETED', delivery_status = 'PACKING' WHERE order_number = ?", numbers[1]);
        jdbc.update("UPDATE tenant_acme.orders SET status = 'CANCELLED', delivery_status = 'OUT_FOR_DELIVERY' WHERE order_number = ?", numbers[2]);
        jdbc.update("UPDATE tenant_acme.orders SET status = 'FAILED', delivery_status = 'DELIVERED' WHERE order_number = ?", numbers[3]);

        // 'status' is the PAYMENT status; enum columns are text, so the order is by name, not by declaration
        assertThat(column("sort=status,asc", "paymentStatus", numbers)).containsExactly("CANCELLED", "COMPLETED", "FAILED", "PENDING");
        assertThat(column("sort=status,desc", "paymentStatus", numbers)).containsExactly("PENDING", "FAILED", "COMPLETED", "CANCELLED");
        assertThat(column("sort=deliveryStatus,asc", "deliveryStatus", numbers))
                .containsExactly("DELIVERED", "OUT_FOR_DELIVERY", "PACKING", "RETURNED");
    }

    private List<String> column(String sort, String field, String[] only) throws Exception {
        String[] kv = sort.split("=", 2);
        MvcResult r = mvc.perform(admin(get("/api/v1/order/all").param(kv[0], kv[1]).param("size", "50"))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        body(r).at("/data/content").forEach(n -> {
            if (List.of(only).contains(n.get("orderNumber").asText())) {
                out.add(n.get(field).asText());
            }
        });
        return out;
    }
}
