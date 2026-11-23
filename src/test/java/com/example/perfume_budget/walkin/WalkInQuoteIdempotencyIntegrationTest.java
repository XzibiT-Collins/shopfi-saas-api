package com.example.perfume_budget.walkin;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WALKIN-BE: API-RD-03 AC1 (quote) + AC2 (Idempotency-Key on the walk-in sale) and API-RD-13 (ledger fault status,
 * customer search without PII in the URL). Real app + Postgres, tenants acme and bravo.
 */
@ExtendWith(OutputCaptureExtension.class)
class WalkInQuoteIdempotencyIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final String ORDER = "/api/v1/admin/walk-in/order";
    private static final String QUOTE = "/api/v1/admin/walk-in/quote";
    private static final String SEARCH = "/api/v1/admin/walk-in/customers/search";

    private Cookie acmeAdmin;
    private List<Long> deactivatedTaxes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        acmeAdmin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        // pin the tax set: one active 10% tax, whatever the fixture holds
        deactivatedTaxes = new ArrayList<>(jdbc.queryForList("SELECT id FROM tenant_acme.taxes WHERE is_active", Long.class));
        jdbc.update("UPDATE tenant_acme.taxes SET is_active = false");
        jdbc.update("INSERT INTO tenant_acme.taxes (name, code, rate, is_active, is_compound, apply_order, created_at) "
                + "VALUES ('Test VAT', 'WIT-VAT', 10.00, true, false, 1, now())");
    }

    @AfterEach
    void cleanWalkIn() {
        for (String schema : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.execute("TRUNCATE " + schema + ".walk_in_orders, " + schema + ".walk_in_customers CASCADE");
        }
        jdbc.update("DELETE FROM tenant_acme.taxes WHERE code = 'WIT-VAT'");
        for (Long id : deactivatedTaxes) jdbc.update("UPDATE tenant_acme.taxes SET is_active = true WHERE id = ?", id);
        jdbc.update("DELETE FROM tenant_acme.front_desk_user_permission_overrides");
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET category = 'SALES_REVENUE' WHERE category = 'WIT_HIDDEN'");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private MvcResult send(MockHttpServletRequestBuilder b, Cookie who, TestTenant t, Object content, String key) throws Exception {
        b.header("X-Tenant-ID", t.slug()).cookie(who);
        if (key != null) b.header("Idempotency-Key", key);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private MvcResult placeAs(Cookie who, TestTenant t, Object content, String key) throws Exception {
        return send(post(ORDER), who, t, content, key);
    }

    private MvcResult place(Object content, String key) throws Exception {
        return placeAs(acmeAdmin, acme, content, key);
    }

    private Map<String, Object> sale(long productId, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", List.of(Map.of("productId", productId, "quantity", qty)));
        m.put("paymentMethod", "CASH");
        m.put("amountPaid", "1000.00");
        return m;
    }

    private static void status(MvcResult r, int expected) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
    }

    private int stock(long p) {
        return jdbc.queryForObject("SELECT stock_quantity FROM tenant_acme.products WHERE id = ?", Integer.class, p);
    }

    private int sold(long p) {
        return jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, p);
    }

    private int walkInJournalEntries() {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE reference_type = 'WALK_IN_ORDER'", Integer.class);
    }

    /** Every table a sale touches, as one comparable string. */
    private String snapshot(long p) {
        String s = "tenant_acme.";
        return String.join("|",
                "orders=" + count(acme, "walk_in_orders"),
                "customers=" + count(acme, "walk_in_customers"),
                "items=" + count(acme, "walk_in_order_items"),
                "taxes=" + count(acme, "walk_in_order_taxes"),
                "journal=" + count(acme, "journal_entries"),
                "lines=" + count(acme, "journal_entry_lines"),
                "movements=" + count(acme, "inventory_movements"),
                "layers=" + jdbc.queryForObject("SELECT coalesce(sum(remaining_quantity),0) FROM " + s + "inventory_layers", Integer.class),
                "ledgerBalance=" + jdbc.queryForObject("SELECT coalesce(sum(balance),0) FROM " + s + "ledger_accounts", BigDecimal.class),
                "stock=" + stock(p), "sold=" + sold(p));
    }

    private Cookie frontDesk(TestTenant t, String email, boolean withWalkInPermission) {
        Long id = jdbc.queryForObject("INSERT INTO " + t.schemaName() + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Desk', ?, 'x', 'FRONT_DESK', true, true, now()) RETURNING id", Long.class, email);
        if (withWalkInPermission) {
            jdbc.update("INSERT INTO " + t.schemaName() + ".front_desk_user_permission_overrides (user_id, permission, allowed, created_at) "
                    + "VALUES (?, 'WALK_IN_ORDER_CREATE', true, now())", id);
        }
        return cookie("accessToken", tenants.runAs(t, () -> jwtUtil.generateAccessToken(email, "FRONT_DESK", id, "Desk")));
    }

    // ------------------------------------------------------------------------------------------ AC1: quote

    @Test
    void quoteReturnsServerComputedTotalsAndWritesNothing() throws Exception {
        long p = product(acme, "Quote Perfume", "50.00", 10);
        String before = snapshot(p);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("items", List.of(Map.of("productId", p, "quantity", 2)));
        req.put("discountType", "PERCENTAGE");
        req.put("discountValue", "10");
        req.put("paymentMethod", "CASH");
        req.put("amountPaid", "500.00");
        MvcResult r = send(post(QUOTE), acmeAdmin, acme, req, null);
        status(r, 200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("originalSubtotal").asText()).isEqualTo("GHS 100.00");
        assertThat(d.get("automaticDiscountAmount").asText()).isEqualTo("GHS 0.00");
        assertThat(d.get("subtotal").asText()).isEqualTo("GHS 100.00");
        assertThat(d.get("discountAmount").asText()).isEqualTo("GHS 10.00");
        assertThat(d.get("discountValue").asText()).isEqualTo("10.00%");
        assertThat(d.get("totalTaxAmount").asText()).isEqualTo("GHS 9.00");
        assertThat(d.get("totalAmount").asText()).isEqualTo("GHS 99.00");
        assertThat(d.get("amountPaid").asText()).isEqualTo("GHS 500.00");
        assertThat(d.get("changeGiven").asText()).isEqualTo("GHS 401.00");
        assertThat(d.get("balanceDue").asText()).isEqualTo("GHS 0.00");
        assertThat(d.at("/taxes/0/taxCode").asText()).isEqualTo("WIT-VAT");
        assertThat(d.at("/items/0/unitPrice").asText()).isEqualTo("GHS 50.00");

        assertThat(snapshot(p)).as("a quote writes no row, no stock, no ledger").isEqualTo(before);

        // the sale of the same draft agrees with the quote to the cent
        req.put("amountPaid", "500.00");
        MvcResult sale = place(req, null);
        status(sale, 200);
        JsonNode o = body(sale).get("data");
        for (String f : new String[]{"originalSubtotal", "automaticDiscountAmount", "subtotal", "discountAmount", "discountValue",
                "totalTaxAmount", "totalAmount", "changeGiven"}) {
            assertThat(o.get(f).asText()).as(f).isEqualTo(d.get(f).asText());
        }
        assertThat(stock(p)).isEqualTo(8);
    }

    @Test
    void quoteWithoutPaymentOmitsChangeAndRejectsWhatTheSaleWouldReject() throws Exception {
        long p = product(acme, "Quote Two", "20.00", 3);
        MvcResult r = send(post(QUOTE), acmeAdmin, acme, Map.of("items", List.of(Map.of("productId", p, "quantity", 1))), null);
        status(r, 200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("totalAmount").asText()).isEqualTo("GHS 22.00");
        assertThat(d.get("amountPaid").isNull()).isTrue();
        assertThat(d.get("changeGiven").isNull()).isTrue();

        // underpaid cash: a quote shows what is still owed instead of failing
        MvcResult under = send(post(QUOTE), acmeAdmin, acme,
                Map.of("items", List.of(Map.of("productId", p, "quantity", 1)), "paymentMethod", "CASH", "amountPaid", "10.00"), null);
        status(under, 200);
        assertThat(body(under).at("/data/balanceDue").asText()).isEqualTo("GHS 12.00");
        assertThat(body(under).at("/data/changeGiven").asText()).isEqualTo("GHS 0.00");

        status(send(post(QUOTE), acmeAdmin, acme, Map.of("items", List.of(Map.of("productId", p, "quantity", 99))), null), 400); // stock
        status(send(post(QUOTE), acmeAdmin, acme, Map.of("items", List.of()), null), 400);
        status(send(post(QUOTE), acmeAdmin, acme, Map.of("items", List.of(Map.of("productId", p, "quantity", 1)),
                "discountType", "PERCENTAGE", "discountValue", "101"), null), 400);
        status(send(post(QUOTE), acmeAdmin, acme, Map.of("items", List.of(Map.of("productId", 987654, "quantity", 1))), null), 404);
    }

    @Test
    void quoteAuthorizationAndTenantScope() throws Exception {
        long p = product(acme, "Quote Auth", "20.00", 3);
        Map<String, Object> req = Map.of("items", List.of(Map.of("productId", p, "quantity", 1)));

        assertThat(mvc.perform(post(QUOTE).header("X-Tenant-ID", "acme").contentType("application/json")
                .content(json.writeValueAsString(req))).andReturn().getResponse().getStatus()).isIn(401, 403);

        Customer cust = customer(acme, "quote-cust@acme.test");
        status(send(post(QUOTE), cookie("accessToken", cust.token()), acme, req, null), 403);

        status(send(post(QUOTE), frontDesk(acme, "fd-ok@acme.test", true), acme, req, null), 200);
        status(send(post(QUOTE), frontDesk(acme, "fd-no@acme.test", false), acme, req, null), 403);

        // tenant bravo cannot price acme's product: it does not exist there
        Cookie bravoAdmin = cookie("accessToken", setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        status(send(post(QUOTE), bravoAdmin, bravo, req, null), 404);
    }

    // ------------------------------------------------------------------------------------------ AC2: idempotency

    @Test
    void sameKeySameBodyReturnsTheSameOrderWithoutASecondSale() throws Exception {
        long p = product(acme, "Idem Perfume", "50.00", 10);
        Map<String, Object> req = sale(p, 2);

        MvcResult first = place(req, "walkin-key-0001");
        status(first, 200);
        JsonNode a = body(first).get("data");
        assertThat(a.get("idempotentReplay").asBoolean()).isFalse();
        String afterFirst = snapshot(p);
        assertThat(stock(p)).isEqualTo(8);

        // a retry serialised differently (amount scale, same lines) is the same request
        Map<String, Object> retry = new LinkedHashMap<>(req);
        retry.put("amountPaid", "1000");
        MvcResult second = place(retry, "walkin-key-0001");
        status(second, 200);
        JsonNode b = body(second).get("data");
        assertThat(b.get("idempotentReplay").asBoolean()).isTrue();
        assertThat(b.get("orderNumber").asText()).isEqualTo(a.get("orderNumber").asText());
        assertThat(b.get("totalAmount").asText()).isEqualTo(a.get("totalAmount").asText());

        assertThat(snapshot(p)).as("replay: no second stock decrement, journal entry, customer or order").isEqualTo(afterFirst);
        assertThat(count(acme, "walk_in_orders")).isEqualTo(1);
        assertThat(walkInJournalEntries()).isEqualTo(1);
        assertThat(sold(p)).isEqualTo(2);
    }

    @Test
    void sameKeyDifferentBodyIs409AndNothingIsWritten() throws Exception {
        long p = product(acme, "Idem Two", "50.00", 10);
        status(place(sale(p, 2), "walkin-key-0002"), 200);
        String after = snapshot(p);

        MvcResult r = place(sale(p, 3), "walkin-key-0002");
        status(r, 409);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        Map<String, Object> otherPayment = sale(p, 2);
        otherPayment.put("paymentMethod", "CARD");
        otherPayment.put("amountPaid", "110.00");
        status(place(otherPayment, "walkin-key-0002"), 409);
        Map<String, Object> otherCustomer = sale(p, 2);
        otherCustomer.put("walkInCustomer", Map.of("name", "Someone Else"));
        status(place(otherCustomer, "walkin-key-0002"), 409);
        assertThat(snapshot(p)).isEqualTo(after);
    }

    @Test
    void sameKeyWithAChangedAmountPaidOrCustomerIsAConflict() throws Exception {
        long p = product(acme, "Idem Fields", "50.00", 10);
        status(place(sale(p, 1), "walkin-key-0003"), 200);
        String after = snapshot(p);
        Map<String, Object> morePaid = sale(p, 1);
        morePaid.put("amountPaid", "2000.00");
        MvcResult r = place(morePaid, "walkin-key-0003");
        status(r, 409);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        Customer registered = customer(acme, "idem-reg@acme.test");
        Map<String, Object> other = sale(p, 1);
        other.put("registeredUserId", registered.id());
        MvcResult r2 = place(other, "walkin-key-0003");
        status(r2, 409);
        assertThat(body(r2).at("/data/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(snapshot(p)).isEqualTo(after);
    }

    @Test
    void invalidKeyIs400AndMissingKeyKeepsTheKeylessBehaviour() throws Exception {
        long p = product(acme, "Idem Three", "10.00", 10);
        for (String bad : new String[]{"short", "has space in it!", "x".repeat(65)}) {
            MvcResult r = place(sale(p, 1), bad);
            status(r, 400);
            assertThat(body(r).at("/data/code").asText()).as(bad).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        }
        assertThat(count(acme, "walk_in_orders")).isZero();
        // no key: each POST is its own sale (documented: send a key)
        status(place(sale(p, 1), null), 200);
        status(place(sale(p, 1), null), 200);
        assertThat(count(acme, "walk_in_orders")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.walk_in_orders WHERE idempotency_key IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void aRejectedSaleDoesNotConsumeTheKey() throws Exception {
        long p = product(acme, "Idem Four", "10.00", 1);
        status(place(sale(p, 5), "walkin-key-0004"), 400); // insufficient stock
        assertThat(count(acme, "walk_in_orders")).isZero();
        status(place(sale(p, 1), "walkin-key-0004"), 200); // same key, now a valid (different) request
        assertThat(count(acme, "walk_in_orders")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneKeyMakeExactlyOneSale() throws Exception {
        long p = product(acme, "Idem Race", "50.00", 50);
        Map<String, Object> req = sale(p, 3);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<MvcResult> c = () -> {
                    go.await();
                    return place(req, "walkin-race-0001");
                };
                futures.add(pool.submit(c));
            }
            go.countDown();
            int replays = 0;
            int fresh = 0;
            java.util.Set<String> numbers = new java.util.HashSet<>();
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                status(r, 200);
                JsonNode d = body(r).get("data");
                numbers.add(d.get("orderNumber").asText());
                if (d.get("idempotentReplay").asBoolean()) replays++; else fresh++;
            }
            assertThat(fresh).isEqualTo(1);
            assertThat(replays).isEqualTo(n - 1);
            assertThat(numbers).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(acme, "walk_in_orders")).isEqualTo(1);
        assertThat(walkInJournalEntries()).isEqualTo(1);
        assertThat(stock(p)).isEqualTo(47);
        assertThat(sold(p)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_movements WHERE reference_type = 'WALK_IN_ORDER'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theKeyIsScopedToTheCashierAndTheTenant() throws Exception {
        long p = product(acme, "Idem Scope", "10.00", 20);
        Cookie desk = frontDesk(acme, "fd-scope@acme.test", true);
        Map<String, Object> req = sale(p, 1);

        MvcResult a = place(req, "shared-key-0001");
        MvcResult b = placeAs(desk, acme, req, "shared-key-0001");
        status(a, 200);
        status(b, 200);
        assertThat(body(b).at("/data/idempotentReplay").asBoolean()).as("other cashier, same key: a new sale").isFalse();
        assertThat(body(b).at("/data/orderNumber").asText()).isNotEqualTo(body(a).at("/data/orderNumber").asText());
        assertThat(count(acme, "walk_in_orders")).isEqualTo(2);

        // tenant bravo: the same key (and the same admin-style login) never sees acme's order
        long bp = product(bravo, "Idem Scope B", "10.00", 20);
        Cookie bravoAdmin = cookie("accessToken", setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        Map<String, Object> breq = sale(bp, 1);
        MvcResult c = placeAs(bravoAdmin, bravo, breq, "shared-key-0001");
        status(c, 200);
        assertThat(body(c).at("/data/idempotentReplay").asBoolean()).isFalse();
        assertThat(body(c).at("/data/orderNumber").asText()).isNotIn(body(a).at("/data/orderNumber").asText(), body(b).at("/data/orderNumber").asText());
        assertThat(count(bravo, "walk_in_orders")).isEqualTo(1);
        assertThat(count(acme, "walk_in_orders")).isEqualTo(2);
        // and a bravo replay with a different body there is a 409 in bravo only
        status(placeAs(bravoAdmin, bravo, sale(bp, 2), "shared-key-0001"), 409);
    }

    // ------------------------------------------------------------------------------------------ RD-13(a)

    @Test
    void aLedgerFaultAnswersAnErrorStatusRollsBackEverythingAndTheKeyCanBeRetried() throws Exception {
        long p = product(acme, "Ledger Fault", "50.00", 10);
        Map<String, Object> req = sale(p, 2);
        String before = snapshot(p);

        // the revenue account is missing: recordWalkInSale throws AccountingException after the stock was consumed
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET category = 'WIT_HIDDEN' WHERE category = 'SALES_REVENUE'");
        MvcResult r = place(req, "walkin-fault-0001");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(500);
        JsonNode b = body(r);
        assertThat(b.at("/data/code").asText()).isEqualTo("ACCOUNTING_ERROR");
        assertThat(b.get("description").asText()).contains("not saved");
        assertThat(r.getResponse().getContentAsString()).as("no ledger detail leaks").doesNotContain("SALES_REVENUE").doesNotContain("Account not found");
        assertThat(snapshot(p)).as("the sale rolled back completely (order, customer, stock, layers, movements, sold count)").isEqualTo(before);

        // ledger repaired: the same key now makes the sale (nothing was left behind by the failed attempt)
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET category = 'SALES_REVENUE' WHERE category = 'WIT_HIDDEN'");
        MvcResult ok = place(req, "walkin-fault-0001");
        status(ok, 200);
        assertThat(body(ok).at("/data/idempotentReplay").asBoolean()).isFalse();
        assertThat(stock(p)).isEqualTo(8);
        assertThat(walkInJournalEntries()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ RD-13(b)

    @Test
    void customerSearchByBodyKeepsNameAndEmailOutOfTheUrlAndTheLogs(CapturedOutput out) throws Exception {
        String email = "pii.person@acme.test";
        customer(acme, email);
        jdbc.update("UPDATE tenant_acme.users SET full_name = 'Zuzanna Pii-Name' WHERE email = ?", email);

        MockHttpServletRequestBuilder b = post(SEARCH);
        MvcResult r = send(b, acmeAdmin, acme, Map.of("query", "pii.person"), null);
        status(r, 200);
        assertThat(body(r).at("/data/0/email").asText()).isEqualTo(email);
        assertThat(r.getRequest().getRequestURI()).isEqualTo(SEARCH);
        assertThat(r.getRequest().getQueryString()).as("no query string at all").isNull();
        assertThat(r.getRequest().getRequestURL().toString()).doesNotContain("pii").doesNotContain("Zuzanna");
        assertThat(out.getAll()).as("search text and found customers never reach the logs")
                .doesNotContain("pii.person").doesNotContain("Zuzanna");

        // blank body query: empty list; over-long: 400
        assertThat(body(send(post(SEARCH), acmeAdmin, acme, Map.of("query", "  "), null)).get("data")).isEmpty();
        status(send(post(SEARCH), acmeAdmin, acme, Map.of("query", "x".repeat(101)), null), 400);
        status(send(post(SEARCH), cookie("accessToken", customer(acme, "c2@acme.test").token()), acme, Map.of("query", "a"), null), 403);
        // tenant scoped: bravo's admin does not find acme's customer
        Cookie bravoAdmin = cookie("accessToken", setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        assertThat(body(send(post(SEARCH), bravoAdmin, bravo, Map.of("query", "pii.person"), null)).get("data")).isEmpty();
    }

    @Test
    void theOldGetSearchStillWorksButIsMarkedDeprecated() throws Exception {
        customer(acme, "legacy.get@acme.test");
        MvcResult r = send(get(SEARCH).param("query", "legacy.get"), acmeAdmin, acme, null, null);
        status(r, 200);
        assertThat(r.getResponse().getHeader("Deprecation")).isEqualTo("true");
        assertThat(r.getResponse().getHeader("Link")).contains("successor-version");
        assertThat(body(r).at("/data/0/email").asText()).isEqualTo("legacy.get@acme.test");
    }

    // ------------------------------------------------------------------------------------------ discountValue fix

    @Test
    void discountValueIsAPercentageForPercentageDiscountsAndMoneyForFlat() throws Exception {
        long p = product(acme, "Discount Print", "100.00", 10);
        Map<String, Object> pct = sale(p, 1);
        pct.put("discountType", "PERCENTAGE");
        pct.put("discountValue", "12.5");
        MvcResult a = place(pct, null);
        status(a, 200);
        assertThat(body(a).at("/data/discountValue").asText()).isEqualTo("12.50%");
        String number = body(a).at("/data/orderNumber").asText();
        assertThat(body(send(get("/api/v1/admin/walk-in/orders/" + number), acmeAdmin, acme, null, null)).at("/data/discountValue").asText())
                .isEqualTo("12.50%");

        Map<String, Object> flat = sale(p, 1);
        flat.put("discountType", "FLAT");
        flat.put("discountValue", "5");
        assertThat(body(place(flat, null)).at("/data/discountValue").asText()).isEqualTo("GHS 5.00");
        assertThat(body(place(sale(p, 1), null)).at("/data/discountValue").asText()).isEqualTo("GHS 0.00");
    }
}
