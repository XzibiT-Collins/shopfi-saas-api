package com.example.perfume_budget.accounting;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CAT-03 (CAT-02 review low 3): {@code GET /admin/accounting/journal-entries} was one unbounded list over the date range.
 * It is now paged like the admin order list: {@code PageResponse} envelope, size capped at 50, a page may start at most
 * at row 10,000, a fixed stable order (transaction date, newest first, id as the tie-break), lines + accounts mapped
 * outside a transaction without LazyInitializationException. The seeded rows live in 2033, away from anything else.
 */
class JournalEntriesPagingIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final String FROM = "2033-03-01";
    static final String TO = "2033-03-31";

    long account;
    String accountName;

    @BeforeEach
    void ledgerAccount() {
        accountName = "Cat03 Cash " + java.util.UUID.randomUUID().toString().substring(0, 6);
        account = jdbc.queryForObject("INSERT INTO tenant_acme.ledger_accounts (code, name, type, category, is_active, balance) "
                + "VALUES (?, ?, 'ASSET', 'CASH', true, 0) RETURNING id", Long.class, "C3-" + accountName.substring(11), accountName);
    }

    @AfterEach
    void dropOwnAccount() {
        // the base class truncates journal_entries after this; first drop the lines that point at this class's account
        jdbc.update("DELETE FROM tenant_acme.journal_entry_lines WHERE account_id = ?", account);
        jdbc.update("DELETE FROM tenant_acme.ledger_accounts WHERE id = ?", account);
    }

    private void entry(String number, LocalDateTime at) {
        long id = jdbc.queryForObject("INSERT INTO tenant_acme.journal_entries (entry_number, description, type, reference_type, "
                + "reference_id, is_manual, transaction_date, created_at) VALUES (?, 'cat03', 'ADJUSTMENT', 'TEST', ?, false, ?, now()) RETURNING id",
                Long.class, number, number, at);
        jdbc.update("INSERT INTO tenant_acme.journal_entry_lines (journal_entry_id, account_id, entry_type, amount, description) "
                + "VALUES (?, ?, 'DEBIT', 1.00, 'l'), (?, ?, 'CREDIT', 1.00, 'l')", id, account, id, account);
    }

    private MvcResult list(String... params) throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        var b = get("/api/v1/admin/accounting/journal-entries").param("startDate", FROM).param("endDate", TO);
        for (int i = 0; i < params.length; i += 2) b.param(params[i], params[i + 1]);
        return mvc.perform(b.header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();
    }

    private JsonNode okPage(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private static List<String> numbers(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(e -> out.add(e.get("entryNumber").asText()));
        return out;
    }

    private void seedMany(int n) {
        for (int i = 0; i < n; i++) entry(String.format("JE-M-%03d", i), LocalDateTime.of(2033, 3, 10, 12, 0).plusMinutes(i));
    }

    @Test
    void pagesWalkEveryEntryOnceInAStableNewestFirstOrderWithLinesMapped() throws Exception {
        LocalDateTime same = LocalDateTime.of(2033, 3, 15, 9, 0);
        entry("JE-A", LocalDateTime.of(2033, 3, 1, 0, 0));          // first instant of the range
        entry("JE-B", LocalDateTime.of(2033, 3, 5, 10, 0));
        entry("JE-C1", same);                                      // three entries at the same instant:
        entry("JE-C2", same);                                      // the id breaks the tie
        entry("JE-C3", same);
        entry("JE-D", LocalDateTime.of(2033, 3, 20, 8, 0));
        entry("JE-E", LocalDateTime.of(2033, 3, 31, 23, 59, 59, 900_000_000)); // last fraction of the end day
        entry("JE-OUT1", LocalDateTime.of(2033, 2, 28, 23, 59, 59));
        entry("JE-OUT2", LocalDateTime.of(2033, 4, 1, 0, 0));

        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode p = okPage(list("page", String.valueOf(page), "size", "3"));
            assertThat(p.get("totalElements").asLong()).isEqualTo(7);
            assertThat(p.get("totalPages").asInt()).isEqualTo(3);
            assertThat(p.get("size").asInt()).isEqualTo(3);
            for (JsonNode e : p.get("content")) {
                assertThat(e.get("lines")).hasSize(2);
                e.get("lines").forEach(l -> assertThat(l.get("accountName").asText()).isEqualTo(accountName));
            }
            walked.addAll(numbers(p));
        }
        assertThat(walked).containsExactly("JE-E", "JE-D", "JE-C3", "JE-C2", "JE-C1", "JE-B", "JE-A");
    }

    @Test
    void defaultSizeIs20AndLargerSizesAreClampedTo50() throws Exception {
        seedMany(55);

        JsonNode def = okPage(list());
        assertThat(def.get("size").asInt()).isEqualTo(20);
        assertThat(def.get("content")).hasSize(20);
        assertThat(def.get("totalElements").asLong()).isEqualTo(55);

        JsonNode big = okPage(list("size", "500"));
        assertThat(big.get("size").asInt()).isEqualTo(50);
        assertThat(big.get("content")).hasSize(50);
        assertThat(numbers(big).get(0)).isEqualTo("JE-M-054");

        JsonNode exact = okPage(list("size", "50", "page", "1"));
        assertThat(numbers(exact)).containsExactly("JE-M-004", "JE-M-003", "JE-M-002", "JE-M-001", "JE-M-000");
    }

    @Test
    void aPageMayStartAtMostAtRow10000() throws Exception {
        entry("JE-ONE", LocalDateTime.of(2033, 3, 2, 0, 0));

        // offset 9,950: allowed (empty), offset 10,000: refused
        assertThat(okPage(list("page", "199", "size", "50")).get("content")).isEmpty();
        MvcResult deep = list("page", "200", "size", "50");
        assertThat(deep.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(deep).at("/data/code").asText()).isEqualTo("INVALID_PAGE");
        assertThat(body(deep).at("/data/maxOffset").asInt()).isEqualTo(10_000);
        assertThat(body(deep).at("/data/size").asInt()).isEqualTo(50);

        // the rule applies to the size AFTER the clamp: 200 * min(500, 50) = 10,000
        MvcResult clamped = list("page", "200", "size", "500");
        assertThat(clamped.getResponse().getStatus()).isEqualTo(400);
        assertThat(okPage(list("page", "100", "size", "500")).get("size").asInt()).isEqualTo(50);

        MvcResult huge = list("page", "1000001", "size", "1");
        assertThat(huge.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(huge).at("/data/code").asText()).isEqualTo("INVALID_PAGE");
    }

    @Test
    void startAfterEndIs400InvalidDateRange() throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        MvcResult r = mvc.perform(get("/api/v1/admin/accounting/journal-entries").param("startDate", TO).param("endDate", FROM)
                .header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    void aSingleDayRangeIsAllowed() throws Exception {
        entry("JE-DAY", LocalDateTime.of(2033, 3, 31, 12, 0));
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        MvcResult r = mvc.perform(get("/api/v1/admin/accounting/journal-entries").param("startDate", TO).param("endDate", TO)
                .header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();

        assertThat(numbers(okPage(r))).containsExactly("JE-DAY");
    }
    /**
     * CAT-04 (CAT-03 review F3): ISO dates outside years 1..9999 are refused with 400 INVALID_DATE_RANGE. Before:
     * {@code endDate=+999999999-12-31} was a 500 (LocalDate.MAX.plusDays) and {@code startDate=-999999999-01-01} a 409
     * (Postgres could not bind the timestamp). The edges 0001-01-01 and 9999-12-31 still work.
     */
    @Test
    void yearsOutside1To9999Are400InvalidDateRange() throws Exception {
        entry("JE-EDGE", LocalDateTime.of(2033, 3, 31, 12, 0));
        String[][] refused = {
                {"endDate", "+999999999-12-31"}, {"startDate", "-999999999-01-01"}, {"endDate", "+10000-01-01"},
                {"startDate", "0000-12-31"}, {"startDate", "-0001-01-01"}};
        for (String[] q : refused) {
            String start = q[0].equals("startDate") ? q[1] : FROM;
            String end = q[0].equals("endDate") ? q[1] : TO;
            MvcResult r = range(start, end);
            assertThat(r.getResponse().getStatus()).as(q[0] + "=" + q[1] + " -> " + r.getResponse().getContentAsString()).isEqualTo(400);
            assertThat(body(r).at("/data/code").asText()).as(q[0] + "=" + q[1]).isEqualTo("INVALID_DATE_RANGE");
        }
        assertThat(numbers(okPage(range("0001-01-01", "9999-12-31")))).containsExactly("JE-EDGE");
    }

    private MvcResult range(String start, String end) throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        return mvc.perform(get("/api/v1/admin/accounting/journal-entries").param("startDate", start).param("endDate", end)
                .header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();
    }
}
