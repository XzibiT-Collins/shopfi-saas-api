package com.example.perfume_budget.accounting;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CAT-06 (CAT-04 review): {@code /income-statement} and {@code /cash-flow} follow the journal-entries date rules:
 * years outside 1..9999 and a start after the end are 400 INVALID_DATE_RANGE (they were a 409 / a quiet empty report),
 * a not-real date (2026-02-30) is a 400 like on journal-entries. {@code /summary} and {@code /balance-sheet} take no dates
 * (point-in-time snapshots): query parameters are ignored, the answer stays 200.
 */
class AccountingDateValidationIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final String BASE = "/api/v1/admin/accounting/";
    private static final List<String> RANGED = List.of("income-statement", "cash-flow", "journal-entries");

    private MvcResult call(String route, String... params) throws Exception {
        Map<String, Object> a = jdbc.queryForMap("SELECT id, email, full_name FROM tenant_acme.users WHERE email = ?", acme.adminEmail());
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken((String) a.get("email"), "ADMIN",
                ((Number) a.get("id")).longValue(), (String) a.get("full_name")));
        var b = get(BASE + route);
        for (int i = 0; i < params.length; i += 2) b.param(params[i], params[i + 1]);
        return mvc.perform(b.header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token))).andReturn();
    }

    private String code(MvcResult r) throws Exception {
        String raw = r.getResponse().getContentAsString();
        return raw.isBlank() ? "" : body(r).at("/data/code").asText();
    }

    @Test
    void yearsOutside1To9999Are400InvalidDateRange_onEveryRangedRoute() throws Exception {
        String[][] refused = {
                {"2033-03-01", "+999999999-12-31"}, {"-999999999-01-01", "2033-03-31"}, {"2033-03-01", "+10000-01-01"},
                {"0000-12-31", "2033-03-31"}, {"-0001-01-01", "2033-03-31"}};
        for (String route : RANGED) {
            for (String[] q : refused) {
                MvcResult r = call(route, "startDate", q[0], "endDate", q[1]);
                assertThat(r.getResponse().getStatus()).as(route + " " + q[0] + ".." + q[1] + " " + r.getResponse().getContentAsString()).isEqualTo(400);
                assertThat(code(r)).as(route + " " + q[0] + ".." + q[1]).isEqualTo("INVALID_DATE_RANGE");
            }
        }
    }

    @Test
    void startAfterEndIs400InvalidDateRange_onEveryRangedRoute() throws Exception {
        for (String route : RANGED) {
            MvcResult r = call(route, "startDate", "2033-03-31", "endDate", "2033-03-01");
            assertThat(r.getResponse().getStatus()).as(route).isEqualTo(400);
            assertThat(code(r)).as(route).isEqualTo("INVALID_DATE_RANGE");
        }
    }

    @Test
    void notRealDatesAre400_theSameAnswerOnEveryRangedRoute() throws Exception {
        MvcResult journal = call("journal-entries", "startDate", "2026-02-30", "endDate", "2026-03-01");
        assertThat(journal.getResponse().getStatus()).isEqualTo(400);
        JsonNode expected = body(journal);
        for (String route : List.of("income-statement", "cash-flow")) {
            for (String[] q : new String[][]{{"2026-02-30", "2026-03-01"}, {"2026-03-01", "2026-04-31"}, {"2026-13-01", "2026-03-01"}}) {
                MvcResult r = call(route, "startDate", q[0], "endDate", q[1]);
                assertThat(r.getResponse().getStatus()).as(route + " " + q[0] + ".." + q[1]).isEqualTo(400);
            }
            MvcResult r = call(route, "startDate", "2026-02-30", "endDate", "2026-03-01");
            assertThat(body(r)).as(route + " same body as journal-entries").isEqualTo(expected);
        }
    }

    @Test
    void theEdgesAndSingleDayStillWork() throws Exception {
        for (String route : List.of("income-statement", "cash-flow")) {
            for (String[] q : new String[][]{{"0001-01-01", "9999-12-31"}, {"2033-03-31", "2033-03-31"}}) {
                MvcResult r = call(route, "startDate", q[0], "endDate", q[1]);
                assertThat(r.getResponse().getStatus()).as(route + " " + q[0] + ".." + q[1] + " " + r.getResponse().getContentAsString()).isEqualTo(200);
            }
        }
    }

    @Test
    void summaryAndBalanceSheetTakeNoDates_parametersAreIgnored() throws Exception {
        for (String route : List.of("summary", "balance-sheet")) {
            assertThat(call(route).getResponse().getStatus()).as(route).isEqualTo(200);
            assertThat(call(route, "startDate", "2026-02-30", "endDate", "+999999999-12-31").getResponse().getStatus())
                    .as(route + " with ignored parameters").isEqualTo(200);
        }
    }
}
