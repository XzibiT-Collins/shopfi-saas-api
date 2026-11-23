package com.example.perfume_budget.platform;

import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API-RD-04 "Find your store" end to end. The contract is NON-ENUMERATION: for an existing store, an unknown one, a
 * malformed or reserved name, and a store that is not ACTIVE (pending, provisioning, failed, suspended) the answer is
 * byte-identical (status, body, headers), the work done on the request thread is the same (one registry lookup, one
 * hand-off to the mail executor), the throttles are keyed on the typed input so a 429 is identical too, and the
 * sign-in link only ever goes to the OWNER address on record of an ACTIVE store.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "onboarding.find-store-ip.capacity=15",
        "onboarding.find-store-ip.period-seconds=3600",
        "onboarding.find-store-slug.capacity=6",
        "onboarding.find-store-slug.period-seconds=3600"
})
class StoreLookupIntegrationTest extends AbstractPostgresIntegrationTest {

    static final AtomicInteger SEQ = new AtomicInteger();
    static final String PATH = "/api/v1/platform/onboarding/find-store";

    @MockitoBean EmailService emailService;
    @MockitoSpyBean MailDispatcher mailDispatcher;
    @MockitoSpyBean TenantRepository tenantRepository;
    @Autowired MockMvc mvc;
    @Autowired TestTenants tenants;

    String prefix;
    String active;
    String pending;
    String suspended;
    String failed;
    String provisioning;

    @BeforeEach
    void stores() {
        Mockito.clearInvocations(emailService, mailDispatcher, tenantRepository);
        tenants.acme();
        String n = "sl" + SEQ.incrementAndGet() + (System.nanoTime() % 10000);
        prefix = n;
        active = tenantRow(n + "-act", TenantStatus.ACTIVE);
        pending = tenantRow(n + "-pen", TenantStatus.PENDING);
        suspended = tenantRow(n + "-sus", TenantStatus.SUSPENDED);
        failed = tenantRow(n + "-fai", TenantStatus.FAILED);
        provisioning = tenantRow(n + "-pro", TenantStatus.PROVISIONING);
        Mockito.clearInvocations(emailService, mailDispatcher, tenantRepository);
    }

    @AfterEach
    void removeSeededTenants() {
        tenantRepository.findAll().stream().filter(t -> t.getSlug().startsWith(prefix)).forEach(tenantRepository::delete);
    }

    private String tenantRow(String slug, TenantStatus status) {
        tenantRepository.save(Tenant.builder().slug(slug).displayName("Shop " + slug).schemaName("tenant_" + slug.replace('-', '_'))
                .status(status).plan(TenantPlan.FREE).ownerEmail("owner-" + slug + "@owner.test").build());
        return slug;
    }

    private static String nextIp() {
        int n = SEQ.incrementAndGet();
        return "10.88." + (n / 250) + "." + (n % 250 + 1);
    }

    private MockHttpServletResponse find(String body, String ip) throws Exception {
        return mvc.perform(post(PATH).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType("application/json").content(body)).andReturn().getResponse();
    }

    private MockHttpServletResponse find(String slug) throws Exception {
        return find("{\"slug\":\"" + slug + "\"}", nextIp());
    }

    /** Everything a client can observe except Date. */
    private static Map<String, Object> observable(MockHttpServletResponse r) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.getStatus());
        m.put("body", r.getContentAsString());
        TreeSet<String> headers = new TreeSet<>();
        for (String h : r.getHeaderNames()) {
            if (!h.equalsIgnoreCase("Date")) {
                headers.add(h + "=" + r.getHeaders(h));
            }
        }
        m.put("headers", headers);
        return m;
    }

    @Test
    void existingStore_answers202_andMailsTheOwnerTheSignInLink() throws Exception {
        MockHttpServletResponse r = find(active);
        assertThat(r.getStatus()).isEqualTo(202);
        assertThat(r.getContentAsString()).contains("If a store with that name exists");
        verify(emailService, timeout(5000)).sendStoreLoginLinkEmail(eq("owner-" + active + "@owner.test"), eq("Shop " + active), eq(active));
    }

    @Test
    void identicalAnswerForExistingUnknownMalformedReservedAndNotActiveStores() throws Exception {
        List<String> inputs = new ArrayList<>(List.of(active, "no-such-store-" + SEQ.incrementAndGet(), "ab", "UPPER Case!", "x".repeat(40),
                "admin", "www", "-bad-", "a--b--c", "störe", pending, suspended, failed, provisioning, "  " + active.toUpperCase() + "  "));
        Map<String, Object> reference = observable(find("zz-reference-" + SEQ.incrementAndGet()));
        assertThat(reference.get("status")).isEqualTo(202);
        for (String in : inputs) {
            assertThat(observable(find(in))).as("input '%s'", in).isEqualTo(reference);
        }
    }

    @Test
    void sameWorkOnTheRequestThread_forEveryInput() throws Exception {
        for (String in : List.of(active, "no-such-store-xyz", "ab", "UPPER Case!", pending, suspended, "admin")) {
            Mockito.clearInvocations(mailDispatcher, tenantRepository);
            assertThat(find(in).getStatus()).isEqualTo(202);
            // exactly one registry lookup (a malformed name looks up a value that cannot exist) ...
            verify(tenantRepository, times(1)).findBySlug(anyString());
            // ... and exactly one hand-off to the mail executor (a no-op task when nothing is sent)
            verify(mailDispatcher, times(1)).submit(anyString(), anyString(), any(Runnable.class));
        }
    }

    @Test
    void onlyAnActiveStoreIsMailed_andOnlyToItsOwnerOnRecord() throws Exception {
        for (String in : List.of("no-such-store-" + SEQ.incrementAndGet(), "ab", "UPPER Case!", "admin", pending, suspended, failed, provisioning)) {
            assertThat(find(in).getStatus()).isEqualTo(202);
        }
        verify(emailService, after(1500).never()).sendStoreLoginLinkEmail(anyString(), anyString(), anyString());
        // an attacker-supplied address in the body is ignored: the owner on record is the only recipient
        MockHttpServletResponse r = find("{\"slug\":\"" + active + "\",\"email\":\"attacker@evil.test\",\"ownerEmail\":\"attacker@evil.test\"}", nextIp());
        assertThat(r.getStatus()).isEqualTo(202);
        verify(emailService, timeout(5000)).sendStoreLoginLinkEmail(eq("owner-" + active + "@owner.test"), anyString(), eq(active));
        verify(emailService, never()).sendStoreLoginLinkEmail(eq("attacker@evil.test"), anyString(), anyString());
    }

    @Test
    void responseNeverEchoesTheInputOrOwnerData() throws Exception {
        String body = find(active).getContentAsString();
        assertThat(body).doesNotContain(active).doesNotContain("owner-").doesNotContain("@").doesNotContain("Shop ");
    }

    @Test
    void validationErrorsAreTheSameForEveryone_400() throws Exception {
        for (String bad : List.of("{}", "{\"slug\":\"\"}", "{\"slug\":\"   \"}", "{\"slug\":\"" + "a".repeat(65) + "\"}", "not json")) {
            MockHttpServletResponse r = find(bad, nextIp());
            assertThat(r.getStatus()).as(bad).isEqualTo(400);
        }
        verify(tenantRepository, never()).findBySlug(anyString());
    }

    @Test
    void perSlugThrottle_isKeyedOnTheTypedName_so429IsIdenticalForKnownAndUnknown() throws Exception {
        String unknown = "no-such-" + SEQ.incrementAndGet() + "-shop";
        List<MockHttpServletResponse> blocked = new ArrayList<>();
        for (String name : List.of(active, unknown)) {
            for (int i = 0; i < 6; i++) {
                assertThat(find(name).getStatus()).as("%s #%d", name, i).isEqualTo(202); // different IP every time
            }
            blocked.add(find(name));
        }
        for (MockHttpServletResponse b : blocked) {
            assertThat(b.getStatus()).isEqualTo(429);
            assertThat(b.getHeader("Retry-After")).isNotNull();
        }
        assertThat(blocked.get(0).getContentAsString()).isEqualTo(blocked.get(1).getContentAsString());
        // the owner got at most 6 mails (the per-store budget) however many IPs asked
        verify(emailService, timeout(5000).times(6)).sendStoreLoginLinkEmail(anyString(), anyString(), eq(active));
        // case and whitespace variants share the bucket
        assertThat(find("  " + active.toUpperCase() + " ").getStatus()).isEqualTo(429);
    }

    @Test
    void perIpThrottle_blocksAcrossDifferentNames_forKnownAndUnknown() throws Exception {
        String ip = nextIp();
        int ok = 0;
        MockHttpServletResponse limited = null;
        for (int i = 0; i < 20 && limited == null; i++) {
            MockHttpServletResponse r = find("{\"slug\":\"name-" + SEQ.incrementAndGet() + "-x" + i + "\"}", ip);
            if (r.getStatus() == 429) {
                limited = r;
            } else {
                assertThat(r.getStatus()).isEqualTo(202);
                ok++;
            }
        }
        assertThat(limited).isNotNull();
        assertThat(ok).isBetween(14, 16); // capacity 15 per hour (plus at most a sliver of refill)
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        // the same IP asking for an EXISTING store is refused identically (the throttle runs before any lookup)
        MockHttpServletResponse existing = find("{\"slug\":\"" + active + "\"}", ip);
        assertThat(existing.getStatus()).isEqualTo(429);
        assertThat(existing.getContentAsString()).isEqualTo(limited.getContentAsString());
        verify(tenantRepository, never()).findBySlug(eq(active));
    }

    @Test
    void publicRoute_needsNoCredentials_andIsPostOnly() throws Exception {
        assertThat(find(active).getStatus()).isEqualTo(202);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PATH)).andReturn()
                .getResponse().getStatus()).isIn(401, 403, 405);
    }
}
