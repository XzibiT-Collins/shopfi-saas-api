package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import com.example.perfume_budget.platform.plan.events.TenantSubscriptionChangedEvent;
import com.example.perfume_budget.platform.plan.service.PublicPlanCache;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestPlans;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared Spring configuration of the PLAN-01 HTTP tests (one cached context for all of them): the real filter chain on
 * PostgreSQL, a signed-in SUPER_ADMIN, and an AFTER_COMMIT listener recording the plan events. Every test starts and ends
 * on the exact V51 seed ({@link TestPlans#resetToSeed()}), so other classes that assert the seed are unaffected.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AbstractPlansIntegrationTest.EventCapture.class)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "platform.cors.allowed-origins=https://console.shopfi.example",
        "platform.tenants.actions-per-minute=1000",
        "onboarding.plans-ip.capacity=60",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0"
})
public abstract class AbstractPlansIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final String PW = "Sup3r-Admin-pass!";
    protected static final String CONSOLE = "https://console.shopfi.example";
    protected static final String PLANS = "/api/v1/platform/plans";
    protected static final String PUBLIC = "/api/v1/platform/onboarding/plans";
    protected static final AtomicInteger SEQ = new AtomicInteger();
    protected static final ObjectMapper JSON = new ObjectMapper();

    /** Records the plan events that reached an AFTER_COMMIT listener (i.e. the transaction committed). */
    @TestConfiguration
    public static class EventCapture {
        public static final List<Object> COMMITTED = new CopyOnWriteArrayList<>();

        @Bean
        Listener planEventListener() {
            return new Listener();
        }

        static class Listener {
            @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
            void plans(PlansChangedEvent e) {
                COMMITTED.add(e);
            }

            @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
            void tenant(TenantSubscriptionChangedEvent e) {
                COMMITTED.add(e);
            }
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected PlatformAdminRepository admins;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected JWTUtil jwtUtil;
    @Autowired protected TestTenants tenants;
    @Autowired protected PublicPlanCache publicPlanCache;

    protected PlatformAdmin admin;
    protected Cookie adminCookie;

    @BeforeEach
    void signInAndReset() throws Exception {
        TestPlans.resetToSeed();
        publicPlanCache.evict();
        EventCapture.COMMITTED.clear();
        admin = admins.save(PlatformAdmin.builder().email("plans" + SEQ.incrementAndGet() + "@platform.test")
                .password(encoder.encode(PW)).fullName("Root").isActive(true).build());
        adminCookie = loginCookie(admin);
    }

    @AfterEach
    void backToSeed() {
        TestPlans.resetToSeed();
        publicPlanCache.evict();
    }

    protected Cookie loginCookie(PlatformAdmin a) throws Exception {
        int n = SEQ.incrementAndGet();
        MockHttpServletResponse r = mvc.perform(post("/api/v1/platform/auth/login")
                        .with(req -> { req.setRemoteAddr("10.88." + (n / 250) + "." + (n % 250 + 1)); return req; })
                        .contentType("application/json")
                        .content("{\"email\":\"" + a.getEmail() + "\",\"password\":\"" + PW + "\"}"))
                .andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return new Cookie("platformAccessToken", r.getCookie("platformAccessToken").getValue());
    }

    protected MockHttpServletResponse call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse();
    }

    /** As the signed-in SUPER_ADMIN, from the console origin, with a JSON body (null = no body). */
    protected MockHttpServletResponse asAdmin(MockHttpServletRequestBuilder b, String body) throws Exception {
        b = b.cookie(adminCookie).header("Origin", CONSOLE);
        if (body != null) {
            b = b.contentType("application/json").content(body);
        }
        return call(b);
    }

    protected static JsonNode body(MockHttpServletResponse r) throws Exception {
        return JSON.readTree(r.getContentAsString());
    }

    protected static long scalar(String sql, Object... args) {
        try (Connection c = PostgresTestDatabase.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String string(String sql, Object... args) {
        try (Connection c = PostgresTestDatabase.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static int exec(String sql, Object... args) {
        try (Connection c = PostgresTestDatabase.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static long auditRows() {
        return scalar("SELECT count(*) FROM master.plan_audit_log");
    }

    protected static long maxAuditId() {
        return scalar("SELECT coalesce(max(id), 0) FROM master.plan_audit_log");
    }

    protected static long version(long planId) {
        return scalar("SELECT version FROM master.plans WHERE id = ?", planId);
    }

    /** The PUT /{id} body that restates the stored plan (from the platform list), with the given version. */
    protected ObjectNodeBuilder planBody(long id) throws Exception {
        JsonNode list = body(asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PLANS), null));
        for (JsonNode p : list.get("data").get("plans")) {
            if (p.get("id").asLong() == id) {
                com.fasterxml.jackson.databind.node.ObjectNode o = JSON.createObjectNode();
                for (String f : List.of("name", "tagline", "description", "monthlyPricePesewas", "annualDiscountPercent",
                        "trialDays", "transactionFeeBps", "isPublic", "isDefaultForSignup", "highlighted", "badgeLabel",
                        "bulletsHeading", "marketingBullets", "sortOrder", "active")) {
                    o.set(f, p.get(f));
                }
                o.put("version", p.get("version").asLong());
                return new ObjectNodeBuilder(o);
            }
        }
        throw new AssertionError("no plan " + id);
    }

    /** Small fluent wrapper so tests read as "plan 2 with price X". */
    protected static final class ObjectNodeBuilder {
        final com.fasterxml.jackson.databind.node.ObjectNode node;

        ObjectNodeBuilder(com.fasterxml.jackson.databind.node.ObjectNode node) {
            this.node = node;
        }

        public ObjectNodeBuilder put(String f, Object v) {
            node.set(f, JSON.valueToTree(v));
            return this;
        }

        public ObjectNodeBuilder raw(String f, JsonNode v) {
            node.set(f, v);
            return this;
        }

        public ObjectNodeBuilder remove(String f) {
            node.remove(f);
            return this;
        }

        public com.fasterxml.jackson.databind.node.ObjectNode fieldsOnly() {
            com.fasterxml.jackson.databind.node.ObjectNode copy = node.deepCopy();
            copy.remove("version");
            return copy;
        }

        @Override
        public String toString() {
            return node.toString();
        }
    }
}
