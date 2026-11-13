package com.example.perfume_budget.tenantauth;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TENANT-05 base: full-app MockMvc tests on the TENANT-02 Postgres harness with acme/bravo.
 * Redis is replaced by an in-memory map so the real OtpServiceImpl runs end to end.
 * Tenant is selected with the dev {@code X-Tenant-ID} header (enabled by the pgtest profile).
 * Each tenant gets a custom domain so OAuth2 redirect_uri host matching can be exercised.
 */
@AutoConfigureMockMvc
@Import(AbstractTenantWebTest.FakeRedisConfig.class)
public abstract class AbstractTenantWebTest extends AbstractPostgresIntegrationTest {

    protected static final String ACME_DOMAIN = "acme-shop.test";
    protected static final String BRAVO_DOMAIN = "bravo-shop.test";

    @Autowired protected MockMvc mvc;
    @Autowired protected TestTenants tenants;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JWTUtil jwtUtil;
    @Autowired protected ObjectMapper json;

    /** In-memory stand-in for Redis (key -> value). */
    protected static final Map<String, Object> redis = FakeRedisTemplate.STORE;
    protected TestTenant acme;
    protected TestTenant bravo;

    @BeforeEach
    void setUpTenantsAndFakeRedis() {
        FakeRedisTemplate.STORE.clear();
        acme = tenants.acme();
        bravo = tenants.bravo();
        jdbc.update("UPDATE master.tenants SET custom_domain = ? WHERE slug = 'acme'", ACME_DOMAIN);
        jdbc.update("UPDATE master.tenants SET custom_domain = ? WHERE slug = 'bravo'", BRAVO_DOMAIN);
    }

    /** RedisTemplate whose value operations and delete are backed by an in-memory map. */
    static class FakeRedisTemplate extends RedisTemplate<String, Object> {
        static final Map<String, Object> STORE = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private final ValueOperations<String, Object> ops = mock(ValueOperations.class);

        FakeRedisTemplate() {
            doAnswer(i -> { STORE.put(i.getArgument(0), i.getArgument(1)); return null; })
                    .when(ops).set(anyString(), any(), any(Duration.class));
            when(ops.get(any())).thenAnswer(i -> STORE.get((String) i.getArgument(0)));
        }

        @Override public ValueOperations<String, Object> opsForValue() { return ops; }
        @Override public Boolean delete(String key) { return STORE.remove(key) != null; }
    }

    @TestConfiguration
    static class FakeRedisConfig {
        @Bean
        static BeanPostProcessor fakeRedisTemplateSwap() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if ("redisTemplate".equals(beanName) && bean instanceof RedisTemplate<?, ?> orig) {
                        FakeRedisTemplate t = new FakeRedisTemplate();
                        t.setConnectionFactory(orig.getConnectionFactory());
                        t.afterPropertiesSet();
                        return t;
                    }
                    return bean;
                }
            };
        }
    }

    @AfterEach
    void resetCustomDomains() {
        jdbc.update("UPDATE master.tenants SET custom_domain = NULL, webhook_token = NULL WHERE slug IN ('acme','bravo')");
        // leave the shared acme/bravo fixtures pristine for other test classes (e.g. PostgresBootTest)
        for (String schema : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.update("DELETE FROM " + schema + ".payments");
            jdbc.update("DELETE FROM " + schema + ".orders");
            jdbc.update("DELETE FROM " + schema + ".payment_gateway_config");
            jdbc.update("DELETE FROM " + schema + ".profiles WHERE user_id IN (SELECT id FROM " + schema + ".users WHERE email NOT LIKE 'admin@%')");
            jdbc.update("DELETE FROM " + schema + ".users WHERE email NOT LIKE 'admin@%'");
            jdbc.update("UPDATE " + schema + ".feature_flags SET enabled = false");
        }
    }

    protected MockHttpServletRequestBuilder postJson(String path, String tenantSlug, Object body) throws Exception {
        MockHttpServletRequestBuilder b = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (tenantSlug != null) {
            b.header("X-Tenant-ID", tenantSlug);
        }
        return b;
    }

    protected MvcResult loginPassword(String tenantSlug, String email, String password) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/login", tenantSlug, Map.of("email", email, "password", password)))
                .andReturn();
    }

    /** Value of a Set-Cookie named {@code name} on the response, or null. */
    protected static String setCookieValue(MvcResult result, String name) {
        List<String> headers = result.getResponse().getHeaders("Set-Cookie");
        for (String h : headers) {
            if (h.startsWith(name + "=")) {
                String v = h.substring(name.length() + 1);
                int end = v.indexOf(';');
                return end >= 0 ? v.substring(0, end) : v;
            }
        }
        return null;
    }

    protected static Cookie cookie(String name, String value) {
        return new Cookie(name, value);
    }

    protected void setLoginOtpFlag(TestTenant t, boolean enabled) {
        jdbc.update("UPDATE " + t.schemaName() + ".feature_flags SET enabled = ? WHERE feature_key = 'LOGIN_OTP'", enabled);
    }

    protected int countUsers(String schema, String email) {
        return jdbc.queryForObject("SELECT count(*) FROM " + schema + ".users WHERE email = ?", Integer.class, email);
    }
}
