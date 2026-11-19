package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.notification.MailTransport;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.FakeRedis;
import com.example.perfume_budget.support.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** AUTH-02 AC3 on the real filter chain: forgot-password and resend-login-otp are throttled (429 + Retry-After). */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.header-resolution.enabled=true",
        "cors.allowed-origins=https://acme.shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "application.auth.rate-limit.forgot-password-ip.capacity=50",
        "application.auth.rate-limit.forgot-password-email.capacity=3",
        "application.auth.rate-limit.resend-login-otp-ip.capacity=4"
})
class AuthThrottlesIntegrationTest extends AbstractPostgresIntegrationTest {

    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired TestTenants tenants;
    @MockitoSpyBean RedisTemplate<String, Object> redisTemplate;
    @MockitoBean MailTransport mailTransport;

    @BeforeEach
    void setUp() {
        tenants.acme();
        FakeRedis.wire(redisTemplate);
    }

    private MockHttpServletResponse send(String path, String json, String ip) throws Exception {
        MockHttpServletRequestBuilder b = post(path).header("Origin", "https://acme.shopfi.test")
                .contentType("application/json").content(json);
        b.with(req -> {
            req.setRemoteAddr(ip);
            return req;
        });
        return mvc.perform(b).andReturn().getResponse();
    }

    @Test
    void forgotPasswordIsThrottledPerIpAndEmail_identicallyForUnknownAddresses() throws Exception {
        String ip = "10.60." + SEQ.incrementAndGet() + ".1";
        String body = "{\"email\":\"forgot" + SEQ.incrementAndGet() + "@x.test\"}";
        for (int i = 0; i < 3; i++) {
            assertThat(send("/api/v1/auth/forgot-password", body, ip).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = send("/api/v1/auth/forgot-password", body, ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isPositive();
        // another address is not locked out by it (AUTH-02 AC1 class)
        assertThat(send("/api/v1/auth/forgot-password", body, "10.61." + SEQ.incrementAndGet() + ".1").getStatus()).isEqualTo(200);
    }

    @Test
    void resendLoginOtpIsThrottledPerIp() throws Exception {
        String ip = "10.62." + SEQ.incrementAndGet() + ".1";
        for (int i = 0; i < 4; i++) {
            assertThat(send("/api/v1/auth/resend-login-otp", "{\"challengeToken\":\"nope-" + i + "\"}", ip).getStatus())
                    .as("request " + i).isNotEqualTo(429);
        }
        MockHttpServletResponse blocked = send("/api/v1/auth/resend-login-otp", "{\"challengeToken\":\"nope-x\"}", ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        assertThat(send("/api/v1/auth/resend-login-otp", "{\"challengeToken\":\"nope-y\"}",
                "10.63." + SEQ.incrementAndGet() + ".1").getStatus()).isNotEqualTo(429);
    }
}
