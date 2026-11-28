package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.AbstractPlansIntegrationTest;
import com.example.perfume_budget.platform.plan.FeatureKind;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.support.InMemoryEntitlementStore;
import com.example.perfume_budget.support.TestPlans;
import com.example.perfume_budget.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN-02 HTTP tests: the real filter chain and services on PostgreSQL. Tenants are plan-test tenants of their own
 * ({@link #tenant}); their plan / status / trial end are set with SQL (the entitlement store is OFF by default, so no cached
 * entry hides the change). Every gated route of the registry is described ONCE here ({@link #rows}), with a request that
 * passes authentication, authorization and bean validation, and a {@link Row#prepare} that puts the tenant exactly at the
 * point where the gate decides.
 */
public abstract class AbstractEntitlementIntegrationTest extends AbstractPlansIntegrationTest {

    protected static final AtomicInteger N = new AtomicInteger();
    /** A plan with nothing: every BOOLEAN off, every LIMIT 0, inactive, not public (created per test, removed by resetToSeed). */
    protected static final long NONE_ID = 9;

    @Autowired protected InMemoryEntitlementStore store;
    @Autowired protected EntitlementService entitlementService;

    /** A plan-test tenant: master id, its seeded admin and a FRONT_DESK user, with access cookies. */
    protected record T(TestTenant t, long id, long adminId, Cookie admin) {
        String schema() {
            return t.schemaName();
        }
    }

    @Autowired protected SubscriptionService subscriptionService;

    @AfterEach
    void storeOffAndClockBack() {
        store.reset();
        setClock(java.time.Clock.systemDefaultZone());
    }

    /** Swaps the Clock of the enforcement and the subscription response (both compute readOnly per request). */
    protected void setClock(java.time.Clock clock) {
        for (Object bean : List.of(entitlementService, subscriptionService)) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(bean);
            org.springframework.test.util.ReflectionTestUtils.setField(target, "clock", clock);
        }
    }

    protected T tenant(String slug) {
        TestTenant t = tenants.ensureTenant(slug);
        long id = scalar("SELECT id FROM master.tenants WHERE slug = ?", slug);
        long adminId = scalar("SELECT id FROM " + t.schemaName() + ".users WHERE email = ?", t.adminEmail());
        return new T(t, id, adminId, token(t, t.adminEmail(), "ADMIN", adminId));
    }

    protected Cookie token(TestTenant t, String email, String role, long userId) {
        return new Cookie("accessToken", tenants.runAs(t, () -> jwtUtil.generateAccessToken(email, role, userId, "Plan Test")));
    }

    /** A user of the tenant with the role (password hash is irrelevant: tokens are minted directly). */
    protected long user(T t, String role, boolean active) {
        String email = "p2-" + role.toLowerCase() + "-" + N.incrementAndGet() + "@" + t.t().slug() + ".test";
        return scalar("INSERT INTO " + t.schema() + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Plan Test', ?, 'x', ?, ?, true, now()) RETURNING id", email, role, active);
    }

    protected String email(T t, long userId) {
        return string("SELECT email FROM " + t.schema() + ".users WHERE id = ?", userId);
    }

    /** A FRONT_DESK user whose every permission is denied by an override. */
    protected Cookie deniedFrontDesk(T t) {
        long id = user(t, "FRONT_DESK", true);
        for (String p : List.of("WALK_IN_ORDER_CREATE", "WALK_IN_ORDER_VIEW", "WALK_IN_ORDER_MARK_RECEIPT_PRINTED", "CUSTOMER_SEARCH",
                "PRODUCT_VIEW_ADMIN_CATALOG", "PRODUCT_VIEW_STOCK_SUMMARY", "STOCK_TRANSFER", "STOCK_CONVERSION")) {
            exec("INSERT INTO " + t.schema() + ".front_desk_user_permission_overrides (user_id, permission, allowed, created_at) "
                    + "VALUES (?, ?, false, now())", id, p);
        }
        return token(t.t(), email(t, id), "FRONT_DESK", id);
    }

    protected static void setPlan(T t, long planId, String status, Instant trialEndsAt) {
        exec("UPDATE master.tenants SET plan_id = ?, subscription_status = ?, trial_ends_at = ? WHERE id = ?",
                planId, status, trialEndsAt == null ? null : java.sql.Timestamp.from(trialEndsAt), t.id());
    }

    protected static void createNonePlan() {
        exec("INSERT INTO master.plans (id, code, name, monthly_price_pesewas, annual_discount_percent, currency, trial_days, "
                + "transaction_fee_bps, is_public, is_default_for_signup, highlighted, marketing_bullets, sort_order, active, version, updated_at) "
                + "VALUES (?, 'NOTHING', 'Nothing', 0, 0, 'GHS', 0, 0, false, false, false, '[]', 999, false, 0, now()) ON CONFLICT (id) DO NOTHING", NONE_ID);
    }

    /** Edits a plan with SQL (no plan event): the in-process public list is evicted by hand, the store is OFF. */
    protected void setLimit(long planId, PlanFeature f, Integer limit) {
        exec("DELETE FROM master.plan_entitlements WHERE plan_id = ? AND feature_key = ?", planId, f.name());
        exec("INSERT INTO master.plan_entitlements (plan_id, feature_key, enabled, limit_value) VALUES (?, ?, true, ?)",
                planId, f.name(), limit);
        publicPlanCache.evict();
    }

    /** Leaves exactly {@code n} storage locations (a fresh tenant is seeded with two, nothing references them yet). */
    protected void trimLocations(T t, long n) {
        exec("DELETE FROM " + t.schema() + ".storage_locations WHERE id IN (SELECT id FROM " + t.schema()
                + ".storage_locations ORDER BY id DESC LIMIT greatest((SELECT count(*) FROM " + t.schema() + ".storage_locations) - ?, 0))", n);
    }

    // ------------------------------------------------------------------------------------------ requests

    protected MockHttpServletResponse send(T t, Cookie who, MockHttpServletRequestBuilder b, String json) throws Exception {
        b.header("X-Tenant-ID", t.t().slug());
        if (who != null) {
            b.cookie(who);
        }
        if (json != null) {
            b.contentType("application/json").content(json);
        }
        return call(b);
    }

    protected static MockHttpServletRequestBuilder req(HttpMethod m, String path) {
        return MockMvcRequestBuilders.request(m, path);
    }

    protected static MockMultipartHttpServletRequestBuilder newProduct(String name) {
        return (MockMultipartHttpServletRequestBuilder) MockMvcRequestBuilders.multipart("/api/v1/product/add-product")
                .param("productName", name).param("currency", "GHS").param("sellingPrice", "100.00")
                .param("costPrice", "60.00").param("isNewProduct", "true").param("isActive", "false")
                .param("isEnlisted", "false");
    }

    protected static String couponJson(String code, boolean active) {
        return "{" + (code == null ? "" : "\"couponCode\":\"" + code + "\",") + "\"discountType\":\"FLAT\",\"discountValue\":5,\"usageLimit\":10,"
                + "\"isActive\":" + active + ",\"startDate\":\"2026-01-01\",\"expirationDate\":\"2099-12-31\"}";
    }

    // ------------------------------------------------------------------------------------------ counted sets

    protected long count(T t, PlanFeature f) {
        return scalar(UsageCounter.COUNTS.get(f).replace("FROM ", "FROM " + t.schema() + "."));
    }

    /** Adds rows to the counted set of {@code f} until it holds {@code target} (never removes any). */
    protected void fillTo(T t, PlanFeature f, long target) {
        long missing = target - count(t, f);
        if (missing <= 0) {
            return;
        }
        String tag = "f" + N.incrementAndGet();
        String s = t.schema();
        switch (f) {
            case MAX_PRODUCTS -> exec("INSERT INTO " + s + ".products (name, slug, sku, stock_quantity, created_at) "
                    + "SELECT 'p2 fill', 'p2-" + tag + "-' || g, 'P2-" + tag + "-' || g, 0, now() FROM generate_series(1, ?) g", missing);
            case MAX_ACTIVE_COUPONS -> exec("INSERT INTO " + s + ".coupons (code, discount_type, discount_value, usage_limit, "
                    + "usage_count, is_active, start_date, expiration_date, created_at) SELECT 'P2" + tag.toUpperCase() + "X' || g, "
                    + "'FLAT', 1, 5, 0, true, DATE '2026-01-01', DATE '2099-12-31', now() FROM generate_series(1, ?) g", missing);
            case MAX_STAFF -> exec("INSERT INTO " + s + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                    + "SELECT 'Fill', 'p2-" + tag + "-' || g || '@fill.test', 'x', 'FRONT_DESK', true, true, now() FROM generate_series(1, ?) g", missing);
            case MAX_LOCATIONS -> exec("INSERT INTO " + s + ".storage_locations (name, type, active) "
                    + "SELECT 'P2 " + tag + " ' || g, 'STORE_ROOM', true FROM generate_series(1, ?) g", missing);
            default -> throw new IllegalArgumentException(f.name());
        }
        assertThat(count(t, f)).isEqualTo(target);
    }

    // ------------------------------------------------------------------------------------------ the registry rows

    /**
     * One gated route x feature of the registry: {@code handler} = Controller#method as annotated. {@code prepare} puts the
     * tenant where the gate decides (content that fires it, a counted set at the plan's limit); {@code request} is then
     * sent. A request that passes the gate may still fail a later business check (400/404/409): only 401/402/403 count.
     */
    protected record Row(String handler, PlanFeature feature, RequiresFeature.Mode mode, Consumer<T> prepare, Req request) {
        boolean read() {
            return mode == RequiresFeature.Mode.ROUTE && request.method() == HttpMethod.GET;
        }
    }

    protected record Req(HttpMethod method, java.util.function.Function<T, MockHttpServletRequestBuilder> builder,
                         java.util.function.Function<T, String> json) {
    }

    private static Req get(String path) {
        return new Req(HttpMethod.GET, t -> req(HttpMethod.GET, path), t -> null);
    }

    private static Req json(HttpMethod m, String path, String body) {
        return new Req(m, t -> req(m, path), t -> body);
    }

    private static Row route(String handler, PlanFeature f, Req r) {
        return new Row(handler, f, RequiresFeature.Mode.ROUTE, t -> { }, r);
    }

    protected List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        PlanFeature F;
        // CUSTOM_DOMAIN
        rows.add(route("TenantCustomDomainController#requestCustomDomain", PlanFeature.CUSTOM_DOMAIN,
                json(HttpMethod.PUT, "/api/v1/admin/custom-domain", "{\"domain\":\"plan02-gate.example.com\"}")));
        rows.add(route("TenantCustomDomainController#verifyCustomDomain", PlanFeature.CUSTOM_DOMAIN,
                json(HttpMethod.POST, "/api/v1/admin/custom-domain/verify", null)));
        // PRODUCT_FAMILIES (routes)
        rows.add(route("ProductFamilyController#getAllFamilies", PlanFeature.PRODUCT_FAMILIES, get("/api/v1/admin/product-families")));
        rows.add(route("ProductFamilyController#getAvailableUoms", PlanFeature.PRODUCT_FAMILIES,
                get("/api/v1/admin/product-families/999999/available-uoms")));
        // WALK_IN_POS (only starting new work)
        F = PlanFeature.WALK_IN_POS;
        rows.add(route("WalkInOrderController#placeWalkInOrder", F, json(HttpMethod.POST, "/api/v1/admin/walk-in/order",
                "{\"items\":[{\"productId\":999999,\"quantity\":1}],\"paymentMethod\":\"CASH\",\"amountPaid\":10}")));
        rows.add(route("WalkInOrderController#quoteWalkInOrder", F, json(HttpMethod.POST, "/api/v1/admin/walk-in/quote",
                "{\"items\":[{\"productId\":999999,\"quantity\":1}]}")));
        rows.add(route("WalkInOrderController#searchCustomersByBody", F,
                json(HttpMethod.POST, "/api/v1/admin/walk-in/customers/search", "{\"query\":\"zz\"}")));
        rows.add(route("WalkInOrderController#searchCustomers", F, get("/api/v1/admin/walk-in/customers/search?query=zz")));
        // SHOP_WIDE_DISCOUNTS writes
        rows.add(route("ShopWideDiscountController#setShopWideDiscount", PlanFeature.SHOP_WIDE_DISCOUNTS,
                json(HttpMethod.PUT, "/api/v1/admin/shop-discount", "{\"label\":\"P2\",\"discountPercentage\":5,"
                        + "\"startAt\":\"2090-01-01T00:00:00\",\"endAt\":\"2090-01-02T00:00:00\"}")));
        rows.add(route("ShopWideDiscountController#deactivate", PlanFeature.SHOP_WIDE_DISCOUNTS,
                json(HttpMethod.DELETE, "/api/v1/admin/shop-discount/999999", null)));
        // INVENTORY_MANAGEMENT
        rows.add(route("InventoryManagementController#adjustInventory", PlanFeature.INVENTORY_MANAGEMENT,
                json(HttpMethod.POST, "/api/v1/admin/inventory/adjustments",
                        "{\"productId\":999999,\"direction\":\"INCREASE\",\"quantity\":1,\"reason\":\"plan test\"}")));
        rows.add(route("InventoryManagementController#getProductInventoryHistory", PlanFeature.INVENTORY_MANAGEMENT,
                get("/api/v1/admin/inventory/products/999999/history")));
        // STOCK_TRANSFERS
        rows.add(route("StockTransferController#transfer", PlanFeature.STOCK_TRANSFERS, json(HttpMethod.POST,
                "/api/v1/admin/inventory/transfers", "{\"productId\":999999,\"fromLocationId\":1,\"toLocationId\":2,\"quantity\":1}")));
        rows.add(route("StockTransferController#history", PlanFeature.STOCK_TRANSFERS, get("/api/v1/admin/inventory/transfers")));
        // STOCK_CONVERSION
        F = PlanFeature.STOCK_CONVERSION;
        rows.add(route("StockConversionController#getReverseConversionTargetVariants", F,
                get("/api/v1/admin/stock-conversions/reverse/999999/variants")));
        rows.add(route("StockConversionController#convertForward", F, json(HttpMethod.POST,
                "/api/v1/admin/stock-conversions/forward", "{\"sourceProductId\":999999,\"quantity\":1}")));
        rows.add(route("StockConversionController#convertReverse", F, json(HttpMethod.POST,
                "/api/v1/admin/stock-conversions/reverse", "{\"sourceProductId\":999999,\"quantity\":1,\"targetProductId\":999998}")));
        // SALES_REPORTS (items-sold/{productId} stays open)
        rows.add(route("SalesReportController#getItemsSold", PlanFeature.SALES_REPORTS, get("/api/v1/admin/inventory/sales/items-sold")));
        // TAX_CONFIG writes
        F = PlanFeature.TAX_CONFIG;
        rows.add(new Row("TaxController#addTax", F, RequiresFeature.Mode.ROUTE, t -> { },
                new Req(HttpMethod.POST, t -> req(HttpMethod.POST, "/api/v1/tax/add-tax"), t -> {
                    int n = N.incrementAndGet();
                    return "{\"name\":\"P2 tax " + n + "\",\"code\":\"P2-" + n + "\",\"rate\":\"1.00\",\"applyOrder\":95,\"isActive\":false}";
                })));
        rows.add(route("TaxController#updateTax", F, json(HttpMethod.PUT, "/api/v1/tax/update/999999",
                "{\"name\":\"P2 x\",\"code\":\"P2-X\",\"rate\":\"1.00\",\"applyOrder\":95}")));
        rows.add(route("TaxController#deleteTax", F, json(HttpMethod.DELETE, "/api/v1/tax/delete/999999", null)));
        // ACCOUNTING (all reads and the manual entry)
        F = PlanFeature.ACCOUNTING;
        String range = "?startDate=2026-01-01&endDate=2026-01-31";
        rows.add(route("AccountingController#getLedgerSummary", F, get("/api/v1/admin/accounting/summary")));
        rows.add(route("AccountingController#getIncomeStatement", F, get("/api/v1/admin/accounting/income-statement" + range)));
        rows.add(route("AccountingController#getBalanceSheet", F, get("/api/v1/admin/accounting/balance-sheet")));
        rows.add(route("AccountingController#getCashFlow", F, get("/api/v1/admin/accounting/cash-flow" + range)));
        rows.add(route("AccountingController#getJournalEntries", F, get("/api/v1/admin/accounting/journal-entries" + range)));
        rows.add(route("AccountingController#getEntriesByReference", F,
                get("/api/v1/admin/accounting/journal-entries/reference?referenceType=ORDER&referenceId=1")));
        rows.add(route("AccountingController#getManualEntries", F, get("/api/v1/admin/accounting/journal-entries/manual")));
        rows.add(route("AccountingController#getMetadata", F, get("/api/v1/admin/accounting/metadata")));
        rows.add(route("BookkeepingController#recordManualEntry", F, json(HttpMethod.POST, "/api/v1/admin/bookkeeping/manual-entry",
                "{\"description\":\"plan test\",\"type\":\"EXPENSE\",\"lines\":[{\"accountCategory\":\"CASH\",\"entryType\":\"DEBIT\",\"amount\":1}]}")));

        // ---- request-content gates
        rows.add(new Row("StoreThemeController#put", PlanFeature.THEME_ADVANCED, RequiresFeature.Mode.CONTENT,
                this::basicTheme, new Req(HttpMethod.PUT, t -> req(HttpMethod.PUT, "/api/v1/theme"), this::themeWithBanner)));
        rows.add(new Row("FeatureFlagController#updateFlag", PlanFeature.LOGIN_OTP, RequiresFeature.Mode.CONTENT,
                t -> exec("UPDATE " + t.schema() + ".feature_flags SET enabled = false WHERE feature_key = 'LOGIN_OTP'"),
                json(HttpMethod.PATCH, "/api/v1/admin/feature-flags/LOGIN_OTP",
                        "{\"adminEnabled\":true,\"frontDeskEnabled\":false,\"customerEnabled\":false}")));
        rows.add(new Row("ProductController#createProduct", PlanFeature.PRODUCT_FAMILIES, RequiresFeature.Mode.CONTENT, t -> { },
                new Req(HttpMethod.POST, t -> newProduct("P2 variant").param("isNewProduct", "false").param("familyId", "999999")
                        .param("uomCode", "BOX"), t -> null)));
        rows.add(new Row("ProductController#updateProduct", PlanFeature.PRODUCT_FAMILIES, RequiresFeature.Mode.CONTENT, t -> { },
                new Req(HttpMethod.PUT, t -> MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/api/v1/product/update/999999")
                        .param("productName", "P2").param("currency", "GHS").param("sellingPrice", "1.00")
                        .param("costPrice", "1.00").param("uomCode", "BOX"), t -> null)));

        // ---- limits: prepare = counted set filled to the plan's limit (the request is the one entry)
        rows.add(new Row("ProductController#createProduct", PlanFeature.MAX_PRODUCTS, RequiresFeature.Mode.LIMIT, t -> { },
                new Req(HttpMethod.POST, t -> newProduct("P2 limit " + N.incrementAndGet()), t -> null)));
        rows.add(new Row("CouponController#createCoupon", PlanFeature.MAX_ACTIVE_COUPONS, RequiresFeature.Mode.LIMIT, t -> { },
                new Req(HttpMethod.POST, t -> req(HttpMethod.POST, "/api/v1/coupon/add-coupon"),
                        t -> couponJson("P2C" + N.incrementAndGet(), true))));
        rows.add(new Row("CouponController#updateCoupon", PlanFeature.MAX_ACTIVE_COUPONS, RequiresFeature.Mode.LIMIT, t -> { },
                new Req(HttpMethod.PUT, t -> req(HttpMethod.PUT, "/api/v1/coupon/" + offCoupon(t)),
                        t -> couponJson(null, true))));
        rows.add(new Row("AdminFrontDeskManagementController#createFrontDeskUser", PlanFeature.MAX_STAFF, RequiresFeature.Mode.LIMIT,
                t -> { }, new Req(HttpMethod.POST, t -> req(HttpMethod.POST, "/api/v1/admin/front-desk/users"),
                t -> "{\"fullName\":\"P2 Staff\",\"email\":\"p2-new-" + N.incrementAndGet() + "@x.test\",\"password\":\"Str0ng!pass\",\"isActive\":true}")));
        rows.add(new Row("AdminFrontDeskManagementController#updateFrontDeskUser", PlanFeature.MAX_STAFF, RequiresFeature.Mode.LIMIT,
                t -> { }, new Req(HttpMethod.PATCH, t -> req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + user(t, "FRONT_DESK", false)),
                t -> "{\"isActive\":true}")));
        rows.add(new Row("AdminFrontDeskManagementController#assignUserToFrontDesk", PlanFeature.MAX_STAFF, RequiresFeature.Mode.LIMIT,
                t -> { }, new Req(HttpMethod.PATCH, t -> req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + user(t, "CUSTOMER", true) + "/assign"),
                t -> null)));
        rows.add(new Row("StorageLocationController#create", PlanFeature.MAX_LOCATIONS, RequiresFeature.Mode.LIMIT, t -> { },
                new Req(HttpMethod.POST, t -> req(HttpMethod.POST, "/api/v1/admin/inventory/locations"),
                        t -> "{\"name\":\"P2 loc " + N.incrementAndGet() + "\",\"type\":\"STORE_ROOM\"}")));
        return rows;
    }

    /** A coupon outside the counted set (switched off by the admin). */
    protected long offCoupon(T t) {
        return scalar("INSERT INTO " + t.schema() + ".coupons (code, discount_type, discount_value, usage_limit, usage_count, "
                + "is_active, limit_deactivated, start_date, expiration_date, created_at) VALUES (?, 'FLAT', 5, 10, 0, false, false, "
                + "DATE '2026-01-01', DATE '2099-12-31', now()) RETURNING id", "P2OFF" + N.incrementAndGet());
    }

    protected MockHttpServletResponse send(T t, Cookie who, Row row) throws Exception {
        row.prepare().accept(t);
        return send(t, who, row.request().builder().apply(t), row.request().json().apply(t));
    }

    // ------------------------------------------------------------------------------------------ theme

    /**
     * Stores a basic theme (hero only, no footer link groups) through the API: removing advanced content is never gated,
     * so this works on every plan and status. Keeps the stored document for {@link #themeWithBanner}.
     */
    protected void basicTheme(T t) {
        try {
            JsonNode d = body(send(t, null, req(HttpMethod.GET, "/api/v1/theme"), null)).get("data");
            com.fasterxml.jackson.databind.node.ObjectNode o = d.deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode home = JSON.createArrayNode();
            for (JsonNode s : o.path("pages").path("home")) {
                if (s.path("type").asText().equals("hero")) {
                    home.add(s);
                }
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) o.get("pages")).set("home", home);
            ((com.fasterxml.jackson.databind.node.ObjectNode) o.get("pages")).set("footer", JSON.createArrayNode());
            o.remove("revision");
            o.remove("updatedAt");
            MockHttpServletResponse r = send(t, t.admin(), req(HttpMethod.PUT, "/api/v1/theme"), o.toString());
            assertThat(r.getStatus()).as("basic theme stored: " + r.getContentAsString()).isEqualTo(200);
            stashedTheme = body(r).get("data").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected String stashedTheme;

    /** The stored basic document plus a visible announcement banner (an advanced element). */
    protected String themeWithBanner(T t) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode o = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(stashedTheme);
            ((com.fasterxml.jackson.databind.node.ArrayNode) o.get("pages").get("home")).add(JSON.readTree(
                    "{\"id\":\"announcement\",\"type\":\"banner\",\"props\":{\"message\":\"Plan test " + N.incrementAndGet()
                            + "\"},\"visible\":true,\"order\":1}"));
            return o.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ plan expectations

    /** Codes of the seeded public plans where the BOOLEAN is enabled (14.3), display order. */
    protected static List<String> plansWith(PlanFeature f) {
        List<String> out = new ArrayList<>();
        for (long id : new long[]{TestPlans.STARTER_ID, TestPlans.GROWTH_ID, TestPlans.SCALE_ID}) {
            if (enabled(id, f)) {
                out.add(code(id));
            }
        }
        return out;
    }

    protected static boolean enabled(long planId, PlanFeature f) {
        if (f.kind() == FeatureKind.LIMIT) {
            throw new IllegalArgumentException();
        }
        return scalar("SELECT count(*) FROM master.plan_entitlements WHERE plan_id = ? AND feature_key = ? AND enabled",
                planId, f.name()) == 1;
    }

    /** LIMIT value of the plan: null = unlimited (-1 sentinel never stored). */
    protected static Integer limit(long planId, PlanFeature f) {
        String v = string("SELECT coalesce(limit_value::text, 'null') FROM master.plan_entitlements WHERE plan_id = ? AND feature_key = ?",
                planId, f.name());
        return v == null ? Integer.valueOf(0) : "null".equals(v) ? null : Integer.valueOf(v);
    }

    protected static String code(long planId) {
        return string("SELECT code FROM master.plans WHERE id = ?", planId);
    }

    protected static void assert402(MockHttpServletResponse r, String code, PlanFeature f, String reason, String what) throws Exception {
        assertThat(r.getStatus()).as(what + ": " + r.getContentAsString()).isEqualTo(402);
        JsonNode b = body(r);
        assertThat(b.at("/data/code").asText()).as(what).isEqualTo(code);
        assertThat(b.at("/data/feature").asText()).as(what).isEqualTo(f.name());
        if (reason != null) {
            assertThat(b.at("/data/reason").asText()).as(what).isEqualTo(reason);
        }
    }

    protected static void assertPassedTheGate(MockHttpServletResponse r, String what) throws Exception {
        assertThat(r.getStatus()).as(what + ": " + r.getContentAsString()).isNotIn(401, 402, 403);
    }
}
