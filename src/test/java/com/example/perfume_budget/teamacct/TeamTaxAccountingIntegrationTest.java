package com.example.perfume_budget.teamacct;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * API-RD-11 (team / tax) and API-RD-12 (accounting). Real app + Postgres, tenants acme and bravo.
 */
class TeamTaxAccountingIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final String TEAM = "/api/v1/admin/front-desk";
    private static final String TAX = "/api/v1/tax";
    private static final String ACCT = "/api/v1/admin/accounting";
    private static final String MANUAL = "/api/v1/admin/bookkeeping/manual-entry";
    private static final String STRONG = "Str0ng!pass";

    private Cookie acmeAdmin;
    private Cookie bravoAdmin;
    private Map<String, Boolean> templateBefore;
    private Map<Long, BigDecimal> balancesBefore;

    @BeforeEach
    void setUp() throws Exception {
        acmeAdmin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        bravoAdmin = cookie("accessToken", setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        templateBefore = new LinkedHashMap<>();
        jdbc.queryForList("SELECT permission, enabled FROM tenant_acme.front_desk_permission_templates")
                .forEach(r -> templateBefore.put((String) r.get("permission"), (Boolean) r.get("enabled")));
        balancesBefore = new LinkedHashMap<>();
        jdbc.queryForList("SELECT id, balance FROM tenant_acme.ledger_accounts")
                .forEach(r -> balancesBefore.put(((Number) r.get("id")).longValue(), (BigDecimal) r.get("balance")));
    }

    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM tenant_acme.front_desk_user_permission_overrides");
        jdbc.update("DELETE FROM tenant_acme.front_desk_permission_templates");
        templateBefore.forEach((p, e) -> jdbc.update("INSERT INTO tenant_acme.front_desk_permission_templates (permission, enabled, created_at) VALUES (?, ?, now())", p, e));
        jdbc.update("DELETE FROM tenant_acme.taxes WHERE code LIKE 'TT-%'");
        balancesBefore.forEach((id, b) -> jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE id = ?", b, id));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private MvcResult send(MockHttpServletRequestBuilder b, Cookie who, TestTenant t, Object content, String ifMatch, String idemKey) throws Exception {
        b.header("X-Tenant-ID", t.slug());
        if (who != null) b.cookie(who);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private MvcResult acmeCall(MockHttpServletRequestBuilder b, Object content, String ifMatch) throws Exception {
        return send(b, acmeAdmin, acme, content, ifMatch, null);
    }

    private static void status(MvcResult r, int expected) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
    }

    private String code(MvcResult r) throws Exception {
        return body(r).at("/data/code").asText();
    }

    private String desc(MvcResult r) throws Exception {
        return body(r).get("description").asText();
    }

    private long staff(String email, String role) {
        return jdbc.queryForObject("INSERT INTO tenant_acme.users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Tt Desk', ?, 'x', ?, true, true, now()) RETURNING id", Long.class, email, role);
    }

    private Map<String, Object> newStaff(String email, String password) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", "Tt Staff");
        m.put("email", email);
        m.put("password", password);
        m.put("isActive", true);
        return m;
    }

    private Map<String, Object> taxBody(String code, String rate, boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Tt " + code);
        m.put("code", code);
        m.put("rate", rate);
        m.put("isActive", active);
        m.put("isCompound", false);
        m.put("applyOrder", 90);
        return m;
    }

    private <T> List<T> parallel(int n, java.util.function.IntFunction<Callable<T>> job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> c = job.apply(i);
                fs.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------ 11a codes

    @Test
    void teamErrorsCarryStableCodesAndKeepTheirDescriptions() throws Exception {
        staff("tt-one@acme.test", "FRONT_DESK");
        MvcResult dup = acmeCall(post(TEAM + "/users"), newStaff("tt-one@acme.test", STRONG), null);
        status(dup, 409);
        assertThat(code(dup)).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(desc(dup)).isEqualTo("An account with this email already exists.");

        long customerId = staff("tt-cust@acme.test", "CUSTOMER");
        MvcResult notDesk = acmeCall(patch(TEAM + "/users/" + customerId), Map.of("fullName", "X"), null);
        status(notDesk, 400);
        assertThat(code(notDesk)).isEqualTo("NOT_FRONT_DESK_USER");
        assertThat(desc(notDesk)).isEqualTo("Selected user is not a front desk user.");

        MvcResult missing = acmeCall(get(TEAM + "/users/999999/permissions"), null, null);
        status(missing, 404);
        assertThat(code(missing)).isEqualTo("USER_NOT_FOUND");
        assertThat(desc(missing)).isEqualTo("User not found.");

        long deskId = staff("tt-two@acme.test", "FRONT_DESK");
        MvcResult conflict = acmeCall(put(TEAM + "/users/" + deskId + "/permissions"),
                Map.of("allowedPermissions", List.of("CUSTOMER_SEARCH"), "deniedPermissions", List.of("CUSTOMER_SEARCH")), null);
        status(conflict, 400);
        assertThat(code(conflict)).isEqualTo("PERMISSION_CONFLICT");
        assertThat(desc(conflict)).isEqualTo("A permission cannot be both allowed and denied.");
    }

    @Test
    void taxErrorsCarryStableCodesAndKeepTheirDescriptions() throws Exception {
        status(acmeCall(post(TAX + "/add-tax"), taxBody("TT-A", "5.00", false), null), 200);
        MvcResult sameCode = acmeCall(post(TAX + "/add-tax"), Map.of("name", "Tt other", "code", "TT-A", "rate", "1.00", "applyOrder", 91), null);
        status(sameCode, 409);
        assertThat(code(sameCode)).isEqualTo("TAX_CODE_EXISTS");
        assertThat(desc(sameCode)).isEqualTo("Tax already exists with code: TT-A");
        MvcResult sameName = acmeCall(post(TAX + "/add-tax"), Map.of("name", "Tt TT-A", "code", "TT-B", "rate", "1.00", "applyOrder", 91), null);
        status(sameName, 409);
        assertThat(code(sameName)).isEqualTo("TAX_NAME_EXISTS");
        assertThat(desc(sameName)).isEqualTo("Tax already exists with name: Tt TT-A");
        MvcResult nf = acmeCall(get(TAX + "/987654"), null, null);
        status(nf, 404);
        assertThat(code(nf)).isEqualTo("TAX_NOT_FOUND");
        assertThat(desc(nf)).isEqualTo("Tax not found with id: 987654");
    }

    // ------------------------------------------------------------------------------------------ 11b password

    @Test
    void staffPasswordsFollowTheCustomerPolicy() throws Exception {
        MvcResult weak = acmeCall(post(TEAM + "/users"), newStaff("tt-weak@acme.test", "abcdefgh"), null);
        status(weak, 400);
        assertThat(code(weak)).isEqualTo("PASSWORD_TOO_WEAK");
        assertThat(desc(weak)).isEqualTo("Password must contain at least one uppercase letter, one number, and one special character.");
        MvcResult shortPw = acmeCall(post(TEAM + "/users"), newStaff("tt-short@acme.test", "Ab1!"), null);
        status(shortPw, 400);
        // unchanged contract: bean-validation shape, no data.code
        assertThat(desc(shortPw)).isEqualTo("Validation failed");
        assertThat(body(shortPw).at("/data/password").asText()).isEqualTo("Password must be at least 8 characters long");
        assertThat(body(shortPw).at("/data/code").isMissingNode()).isTrue();
        MvcResult paddedShort = acmeCall(post(TEAM + "/users"), newStaff("tt-pad@acme.test", "  Ab1!xy  "), null);
        status(paddedShort, 400);
        assertThat(code(paddedShort)).isEqualTo("PASSWORD_TOO_SHORT");
        MvcResult longPw = acmeCall(post(TEAM + "/users"), newStaff("tt-long@acme.test", "Aa1!" + "x".repeat(70)), null);
        status(longPw, 400);
        assertThat(code(longPw)).isEqualTo("PASSWORD_TOO_LONG");
        assertThat(countUsers("tenant_acme", "tt-weak@acme.test") + countUsers("tenant_acme", "tt-short@acme.test")
                + countUsers("tenant_acme", "tt-long@acme.test")).isZero();

        MvcResult ok = acmeCall(post(TEAM + "/users"), newStaff("tt-ok@acme.test", STRONG), null);
        status(ok, 200);
        long id = body(ok).at("/data/id").asLong();
        String hash = jdbc.queryForObject("SELECT password FROM tenant_acme.users WHERE id = ?", String.class, id);

        MvcResult weakPatch = acmeCall(patch(TEAM + "/users/" + id), Map.of("password", "alllowercase1"), null);
        status(weakPatch, 400);
        assertThat(code(weakPatch)).isEqualTo("PASSWORD_TOO_WEAK");
        assertThat(jdbc.queryForObject("SELECT password FROM tenant_acme.users WHERE id = ?", String.class, id)).isEqualTo(hash);
        MvcResult shortPatch = acmeCall(patch(TEAM + "/users/" + id), Map.of("password", "Ab1!"), null);
        assertThat(code(shortPatch)).isEqualTo("PASSWORD_TOO_SHORT");
        assertThat(desc(shortPatch)).isEqualTo("Password must be at least 8 characters long");
        status(acmeCall(patch(TEAM + "/users/" + id), Map.of("password", "An0ther!pass"), null), 200);
        assertThat(jdbc.queryForObject("SELECT password FROM tenant_acme.users WHERE id = ?", String.class, id)).isNotEqualTo(hash);
        // a blank password on PATCH still means "leave it unchanged"
        status(acmeCall(patch(TEAM + "/users/" + id), Map.of("password", "  "), null), 200);
    }

    // ------------------------------------------------------------------------------------------ 11d summary

    @Test
    void staffListCarriesAPermissionSummaryPerUser() throws Exception {
        status(acmeCall(put(TEAM + "/template"), Map.of("permissions", List.of("WALK_IN_ORDER_CREATE", "CUSTOMER_SEARCH", "WALK_IN_ORDER_VIEW")), null), 200);
        long plain = staff("tt-plain@acme.test", "FRONT_DESK");
        long custom = staff("tt-custom@acme.test", "FRONT_DESK");
        long noop = staff("tt-noop@acme.test", "FRONT_DESK");
        status(acmeCall(put(TEAM + "/users/" + custom + "/permissions"),
                Map.of("allowedPermissions", List.of("STOCK_TRANSFER", "STOCK_CONVERSION"), "deniedPermissions", List.of("CUSTOMER_SEARCH")), null), 200);
        // already granted by the template / already absent: overrides that change nothing are not "custom"
        status(acmeCall(put(TEAM + "/users/" + noop + "/permissions"),
                Map.of("allowedPermissions", List.of("CUSTOMER_SEARCH"), "deniedPermissions", List.of("STOCK_TRANSFER")), null), 200);
        MvcResult r = acmeCall(get(TEAM + "/users"), null, null);
        status(r, 200);
        Map<Long, JsonNode> byId = new LinkedHashMap<>();
        body(r).get("data").forEach(u -> byId.put(u.get("id").asLong(), u.get("permissionSummary")));
        assertThat(byId.get(plain).toString()).isEqualTo("{\"effectiveCount\":3,\"totalCount\":8,\"allowedOverrideCount\":0,\"deniedOverrideCount\":0,\"isCustom\":false}");
        assertThat(byId.get(custom).toString()).isEqualTo("{\"effectiveCount\":4,\"totalCount\":8,\"allowedOverrideCount\":2,\"deniedOverrideCount\":1,\"isCustom\":true}");
        assertThat(byId.get(noop).toString()).isEqualTo("{\"effectiveCount\":3,\"totalCount\":8,\"allowedOverrideCount\":0,\"deniedOverrideCount\":0,\"isCustom\":false}");
    }

    // ------------------------------------------------------------------------------------------ 11c concurrency

    @Test
    void defaultTemplateSaveHonoursIfMatch() throws Exception {
        MvcResult read = acmeCall(get(TEAM + "/template"), null, null);
        status(read, 200);
        String etag = read.getResponse().getHeader("ETag");
        assertThat(etag).isEqualTo("\"" + body(read).at("/data/version").asText() + "\"");

        MvcResult first = acmeCall(put(TEAM + "/template"), Map.of("permissions", List.of("CUSTOMER_SEARCH")), etag);
        status(first, 200);
        String etag2 = first.getResponse().getHeader("ETag");
        assertThat(etag2).isNotEqualTo(etag);

        MvcResult stale = acmeCall(put(TEAM + "/template"), Map.of("permissions", List.of("STOCK_TRANSFER")), etag);
        status(stale, 409);
        assertThat(code(stale)).isEqualTo("TEMPLATE_VERSION_CONFLICT");
        assertThat(body(stale).at("/data/currentVersion").asText()).isEqualTo(etag2.replace("\"", ""));
        assertThat(body(acmeCall(get(TEAM + "/template"), null, null)).at("/data/permissions").toString()).isEqualTo("[\"CUSTOMER_SEARCH\"]");

        // no header: last write wins (documented, deprecated); wildcard and weak form are accepted; junk is a 400
        status(acmeCall(put(TEAM + "/template"), Map.of("permissions", List.of("STOCK_TRANSFER")), null), 200);
        status(acmeCall(put(TEAM + "/template"), Map.of("permissions", List.of("STOCK_TRANSFER")), "*"), 200);
        MvcResult junk = acmeCall(put(TEAM + "/template"), Map.of("permissions", List.of()), "3");
        status(junk, 400);
        assertThat(code(junk)).isEqualTo("INVALID_IF_MATCH");
    }

    @Test
    void concurrentTemplateSavesWithOneEtagLetExactlyOneWin() throws Exception {
        String etag = acmeCall(get(TEAM + "/template"), null, null).getResponse().getHeader("ETag");
        List<Integer> statuses = parallel(6, i -> () -> acmeCall(put(TEAM + "/template"),
                Map.of("permissions", List.of(i % 2 == 0 ? "STOCK_TRANSFER" : "CUSTOMER_SEARCH", "WALK_IN_ORDER_VIEW")), etag)
                .getResponse().getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);
    }

    @Test
    void userPermissionSaveHonoursIfMatchAndSerialises() throws Exception {
        long id = staff("tt-perm@acme.test", "FRONT_DESK");
        MvcResult read = acmeCall(get(TEAM + "/users/" + id + "/permissions"), null, null);
        status(read, 200);
        String etag = read.getResponse().getHeader("ETag");
        assertThat(etag).isEqualTo("\"" + body(read).at("/data/version").asText() + "\"");

        List<Integer> statuses = parallel(6, i -> () -> acmeCall(put(TEAM + "/users/" + id + "/permissions"),
                Map.of("allowedPermissions", List.of(i % 2 == 0 ? "STOCK_TRANSFER" : "STOCK_CONVERSION"), "deniedPermissions", List.of()), etag)
                .getResponse().getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.front_desk_user_permission_overrides WHERE user_id = ?", Integer.class, id)).isEqualTo(1);

        MvcResult stale = acmeCall(put(TEAM + "/users/" + id + "/permissions"),
                Map.of("allowedPermissions", List.of(), "deniedPermissions", List.of()), etag);
        status(stale, 409);
        assertThat(code(stale)).isEqualTo("USER_PERMISSIONS_VERSION_CONFLICT");
        // the token is per user: another user's token does not unlock this one
        long other = staff("tt-perm2@acme.test", "FRONT_DESK");
        String otherEtag = acmeCall(get(TEAM + "/users/" + other + "/permissions"), null, null).getResponse().getHeader("ETag");
        status(acmeCall(put(TEAM + "/users/" + id + "/permissions"), Map.of("allowedPermissions", List.of(), "deniedPermissions", List.of()), otherEtag), 409);
        // header absent: still works
        status(acmeCall(put(TEAM + "/users/" + id + "/permissions"), Map.of("allowedPermissions", List.of(), "deniedPermissions", List.of()), null), 200);
    }

    @Test
    void taxActiveSaveHonoursIfMatchAndSerialises() throws Exception {
        MvcResult created = acmeCall(post(TAX + "/add-tax"), taxBody("TT-ETAG", "5.00", true), null);
        status(created, 200);
        long id = body(created).at("/data/id").asLong();
        String etag = created.getResponse().getHeader("ETag");
        assertThat(etag).isEqualTo("\"" + body(created).at("/data/version").asText() + "\"");
        assertThat(acmeCall(get(TAX + "/" + id), null, null).getResponse().getHeader("ETag")).isEqualTo(etag);
        // the list carries the same token per tax, so the screen does not need one GET per row
        JsonNode listed = null;
        for (JsonNode t : body(acmeCall(get(TAX + "/all"), null, null)).get("data")) if (t.get("id").asLong() == id) listed = t;
        assertThat(listed.get("version").asText() + "|").isEqualTo(etag.replace("\"", "") + "|");

        List<Integer> statuses = parallel(6, i -> () -> acmeCall(put(TAX + "/update/" + id),
                taxBody("TT-ETAG", (6 + i) + ".00", i % 2 == 1), etag).getResponse().getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);

        MvcResult stale = acmeCall(put(TAX + "/update/" + id), taxBody("TT-ETAG", "7.00", true), etag);
        status(stale, 409);
        assertThat(code(stale)).isEqualTo("TAX_VERSION_CONFLICT");
        BigDecimal afterRace = jdbc.queryForObject("SELECT rate FROM tenant_acme.taxes WHERE id = ?", BigDecimal.class, id);
        assertThat(afterRace).isBetween(new BigDecimal("6.00"), new BigDecimal("11.00"));
        String fresh = acmeCall(get(TAX + "/" + id), null, null).getResponse().getHeader("ETag");
        status(acmeCall(put(TAX + "/update/" + id), taxBody("TT-ETAG", "7.00", true), fresh), 200);
        assertThat(jdbc.queryForObject("SELECT rate FROM tenant_acme.taxes WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo("7.00");
        status(acmeCall(put(TAX + "/update/" + id), taxBody("TT-ETAG", "7.00", true), null), 200);
    }

    @Test
    void teamAndTaxSavesAreAdminOnlyAndTenantScoped() throws Exception {
        Cookie desk = cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken("tt-desk@acme.test", "FRONT_DESK", staff("tt-desk@acme.test", "FRONT_DESK"), "Desk")));
        Cookie cust = cookie("accessToken", customer(acme, "tt-c@acme.test").token());
        String etag = acmeCall(get(TEAM + "/template"), null, null).getResponse().getHeader("ETag");
        long taxId = body(acmeCall(post(TAX + "/add-tax"), taxBody("TT-AUTHZ", "5.00", true), null)).at("/data/id").asLong();
        long userId = staff("tt-target@acme.test", "FRONT_DESK");
        Object perms = Map.of("allowedPermissions", List.of(), "deniedPermissions", List.of());
        for (Cookie who : new Cookie[]{desk, cust, null}) {
            int expected = who == null ? 401 : 403;
            status(send(put(TEAM + "/template"), who, acme, Map.of("permissions", List.of()), etag, null), expected);
            status(send(put(TEAM + "/users/" + userId + "/permissions"), who, acme, perms, null, null), expected);
            status(send(put(TAX + "/update/" + taxId), who, acme, taxBody("TT-AUTHZ", "9.00", true), null, null), expected);
            status(send(get(TEAM + "/users"), who, acme, null, null, null), expected);
        }
        assertThat(jdbc.queryForObject("SELECT rate FROM tenant_acme.taxes WHERE id = ?", BigDecimal.class, taxId)).isEqualByComparingTo("5.00");
        // another tenant's admin saving acme's template changes bravo's own template only; acme's token is untouched
        status(send(put(TEAM + "/template"), bravoAdmin, bravo, Map.of("permissions", List.of("STOCK_TRANSFER")), null, null), 200);
        assertThat(acmeCall(get(TEAM + "/template"), null, null).getResponse().getHeader("ETag")).isEqualTo(etag);
        // acme's staff id is unknown in bravo's schema
        MvcResult cross = send(get(TEAM + "/users/" + userId + "/permissions"), bravoAdmin, bravo, null, null, null);
        assertThat(cross.getResponse().getStatus()).isIn(400, 404);
        jdbc.update("DELETE FROM tenant_bravo.front_desk_permission_templates");
    }

    // ------------------------------------------------------------------------------------------ 12 accounting

    private long account(String category) {
        return jdbc.queryForObject("SELECT id FROM tenant_acme.ledger_accounts WHERE category = ?", Long.class, category);
    }

    private void journal(String number, LocalDateTime at, String debitCategory, String creditCategory, String amount) {
        long id = jdbc.queryForObject("INSERT INTO tenant_acme.journal_entries (entry_number, description, type, reference_type, reference_id, is_manual, "
                + "transaction_date, created_at) VALUES (?, 'rd12', 'ADJUSTMENT', 'TEST', ?, false, ?, now()) RETURNING id", Long.class, number, number, at);
        jdbc.update("INSERT INTO tenant_acme.journal_entry_lines (journal_entry_id, account_id, entry_type, amount, description) VALUES (?, ?, 'DEBIT', ?, 'l'), (?, ?, 'CREDIT', ?, 'l')",
                id, account(debitCategory), new BigDecimal(amount), id, account(creditCategory), new BigDecimal(amount));
    }

    @Test
    void incomeStatementAndCashFlowUseTheJournalsHalfOpenDayWindow() throws Exception {
        journal("JE-RD12-A", LocalDateTime.of(2033, 5, 15, 23, 59, 59, 500_000_000), "CASH", "SALES_REVENUE", "40.00");
        journal("JE-RD12-B", LocalDateTime.of(2033, 5, 15, 23, 59, 59), "CASH", "SALES_REVENUE", "2.00");
        journal("JE-RD12-C", LocalDateTime.of(2033, 5, 16, 0, 0, 0), "CASH", "SALES_REVENUE", "1000.00");
        journal("JE-RD12-D", LocalDateTime.of(2033, 5, 14, 23, 59, 59, 999_000_000), "CASH", "SALES_REVENUE", "5000.00");

        MvcResult journalList = acmeCall(get(ACCT + "/journal-entries").param("startDate", "2033-05-15").param("endDate", "2033-05-15"), null, null);
        status(journalList, 200);
        assertThat(body(journalList).at("/data/totalElements").asInt()).isEqualTo(2);

        MvcResult is = acmeCall(get(ACCT + "/income-statement").param("startDate", "2033-05-15").param("endDate", "2033-05-15"), null, null);
        status(is, 200);
        assertThat(body(is).at("/data/totalRevenue").decimalValue()).isEqualByComparingTo("42.00");
        MvcResult cf = acmeCall(get(ACCT + "/cash-flow").param("startDate", "2033-05-15").param("endDate", "2033-05-15"), null, null);
        status(cf, 200);
        assertThat(body(cf).at("/data/totalInflows").decimalValue()).isEqualByComparingTo("42.00");
        assertThat(body(cf).at("/data/dailyCashFlows").size()).isEqualTo(1);
        // a two-day range picks up the next day's midnight entry too
        MvcResult two = acmeCall(get(ACCT + "/income-statement").param("startDate", "2033-05-15").param("endDate", "2033-05-16"), null, null);
        assertThat(body(two).at("/data/totalRevenue").decimalValue()).isEqualByComparingTo("1042.00");
    }

    private Map<String, Object> manual(String type, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("description", "Tt entry");
        m.put("type", type);
        m.put("lines", List.of(
                Map.of("accountCategory", "CASH", "entryType", "DEBIT", "amount", amount, "description", "in"),
                Map.of("accountCategory", "OWNERS_CAPITAL", "entryType", "CREDIT", "amount", amount, "description", "capital")));
        return m;
    }

    private int manualEntries() {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE is_manual", Integer.class);
    }

    private BigDecimal cash() {
        return jdbc.queryForObject("SELECT balance FROM tenant_acme.ledger_accounts WHERE category = 'CASH'", BigDecimal.class);
    }

    @Test
    void manualEntryRefusesZeroNegativeAndOverPreciseAmounts() throws Exception {
        BigDecimal before = cash();
        for (String bad : new String[]{"0", "0.00", "-5.00", "10.005", "100000000000000000.00"}) {
            MvcResult r = send(post(MANUAL), acmeAdmin, acme, manual("ADJUSTMENT", bad), null, null);
            status(r, 400);
            assertThat(code(r)).as(bad).isEqualTo("INVALID_AMOUNT");
        }
        // an offsetting pair of a positive and a negative line used to balance and silently flip a side
        Map<String, Object> flipped = manual("ADJUSTMENT", "10.00");
        flipped.put("lines", List.of(
                Map.of("accountCategory", "CASH", "entryType", "DEBIT", "amount", "10.00"),
                Map.of("accountCategory", "OWNERS_CAPITAL", "entryType", "DEBIT", "amount", "-10.00")));
        status(send(post(MANUAL), acmeAdmin, acme, flipped, null, null), 400);
        assertThat(manualEntries()).isZero();
        assertThat(cash()).isEqualByComparingTo(before);
        status(send(post(MANUAL), acmeAdmin, acme, manual("ADJUSTMENT", "10.50"), null, null), 200);
        assertThat(cash()).isEqualByComparingTo(before.add(new BigDecimal("10.50")));
    }

    @Test
    void manualEntryBodyShapeErrorsAre400Not500() throws Exception {
        Map<String, Object> nullLine = manual("EXPENSE", "1.00");
        nullLine.put("lines", java.util.Arrays.asList(null, Map.of("accountCategory", "CASH", "entryType", "DEBIT", "amount", "1.00")));
        status(send(post(MANUAL), acmeAdmin, acme, nullLine, null, null), 400);
        Map<String, Object> noAmount = manual("EXPENSE", "1.00");
        noAmount.put("lines", List.of(Map.of("accountCategory", "CASH", "entryType", "DEBIT"),
                Map.of("accountCategory", "OWNERS_CAPITAL", "entryType", "CREDIT", "amount", "1.00")));
        status(send(post(MANUAL), acmeAdmin, acme, noAmount, null, null), 400);
        assertThat(manualEntries()).isZero();
    }

    @Test
    void manualEntryIsIdempotentPerAdminAndKey() throws Exception {
        BigDecimal before = cash();
        String key = "tt-key-0001";
        MvcResult first = send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "25.00"), null, key);
        status(first, 200);
        assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("false");
        MvcResult replay = send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "25.000"), null, key);
        status(replay, 200);
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(manualEntries()).isEqualTo(1);
        assertThat(cash()).isEqualByComparingTo(before.add(new BigDecimal("25.00")));

        MvcResult other = send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "26.00"), null, key);
        status(other, 409);
        assertThat(code(other)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(manualEntries()).isEqualTo(1);

        MvcResult bad = send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "25.00"), null, "short");
        status(bad, 400);
        assertThat(code(bad)).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        // no key: each POST is its own entry (unchanged, documented)
        status(send(post(MANUAL), acmeAdmin, acme, manual("ADJUSTMENT", "1.00"), null, null), 200);
        status(send(post(MANUAL), acmeAdmin, acme, manual("ADJUSTMENT", "1.00"), null, null), 200);
        assertThat(manualEntries()).isEqualTo(3);
        // a refused body does not consume the key
        MvcResult refused = send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "-1"), null, "tt-key-0002");
        status(refused, 400);
        status(send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "3.00"), null, "tt-key-0002"), 200);
    }

    @Test
    void concurrentManualEntriesWithOneKeyPostOnce() throws Exception {
        BigDecimal before = cash();
        List<Integer> statuses = parallel(6, i -> () -> send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "7.00"), null, "tt-key-race1")
                .getResponse().getStatus());
        assertThat(statuses).containsOnly(200);
        assertThat(manualEntries()).isEqualTo(1);
        assertThat(cash()).isEqualByComparingTo(before.add(new BigDecimal("7.00")));
    }

    @Test
    void manualEntryIsAdminOnlyAndKeysDoNotCrossTenants() throws Exception {
        Cookie desk = cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken("tt-d2@acme.test", "FRONT_DESK", staff("tt-d2@acme.test", "FRONT_DESK"), "Desk")));
        status(send(post(MANUAL), desk, acme, manual("EXPENSE", "1.00"), null, "tt-key-authz"), 403);
        status(send(post(MANUAL), null, acme, manual("EXPENSE", "1.00"), null, "tt-key-authz"), 401);
        assertThat(manualEntries()).isZero();
        BigDecimal bravoCash = jdbc.queryForObject("SELECT balance FROM tenant_bravo.ledger_accounts WHERE category = 'CASH'", BigDecimal.class);
        status(send(post(MANUAL), bravoAdmin, bravo, manual("EXPENSE", "2.00"), null, "tt-key-cross"), 200);
        status(send(post(MANUAL), acmeAdmin, acme, manual("EXPENSE", "2.00"), null, "tt-key-cross"), 200);
        assertThat(manualEntries()).isEqualTo(1);
        jdbc.update("UPDATE tenant_bravo.ledger_accounts SET balance = ? WHERE category = 'CASH'", bravoCash);
        jdbc.update("UPDATE tenant_bravo.ledger_accounts SET balance = 0 WHERE category = 'OWNERS_CAPITAL'");
        jdbc.execute("TRUNCATE tenant_bravo.journal_entries CASCADE");
    }

    @Test
    void metadataOffersExpenseAndTheBalanceSheetRetainedEarningsDoesNotClash() throws Exception {
        MvcResult meta = acmeCall(get(ACCT + "/metadata"), null, null);
        status(meta, 200);
        List<String> types = new ArrayList<>();
        body(meta).at("/data/journalEntryTypes").forEach(t -> types.add(t.get("value").asText()));
        if (types.isEmpty()) body(meta).get("data").fields().forEachRemaining(f -> f.getValue().forEach(t -> { if (t.has("value")) types.add(t.get("value").asText()); }));
        assertThat(types).contains("EXPENSE", "ADJUSTMENT");

        MvcResult bs = acmeCall(get(ACCT + "/balance-sheet"), null, null);
        status(bs, 200);
        List<String> codes = new ArrayList<>();
        body(bs).at("/data/equityAccounts").forEach(a -> codes.add(a.get("accountCode") != null ? a.get("accountCode").asText() : a.get("code").asText()));
        assertThat(codes).containsOnlyOnce("3200").containsOnlyOnce("RETAINED_EARNINGS");
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void everyReportCarriesTheLedgerCurrency() throws Exception {
        for (String route : new String[]{"/summary", "/balance-sheet"}) {
            assertThat(body(acmeCall(get(ACCT + route), null, null)).at("/data/currency").asText()).as(route).isEqualTo("GHS");
        }
        for (String route : new String[]{"/income-statement", "/cash-flow"}) {
            MvcResult r = acmeCall(get(ACCT + route).param("startDate", "2033-01-01").param("endDate", "2033-01-02"), null, null);
            assertThat(body(r).at("/data/currency").asText()).as(route).isEqualTo("GHS");
        }
        journal("JE-RD12-CUR", LocalDateTime.of(2033, 1, 1, 10, 0), "CASH", "SALES_REVENUE", "1.00");
        MvcResult page = acmeCall(get(ACCT + "/journal-entries").param("startDate", "2033-01-01").param("endDate", "2033-01-02"), null, null);
        assertThat(body(page).at("/data/content/0/currency").asText()).isEqualTo("GHS");
    }

    @Test
    void journalCannotPagePastRow10000AndSaysSo() throws Exception {
        MvcResult r = acmeCall(get(ACCT + "/journal-entries").param("startDate", "2033-01-01").param("endDate", "2033-01-02")
                .param("page", "200").param("size", "50"), null, null);
        status(r, 400);
        assertThat(code(r)).isEqualTo("INVALID_PAGE");
        assertThat(body(r).at("/data/maxOffset").asInt()).isEqualTo(10000);
    }
}
