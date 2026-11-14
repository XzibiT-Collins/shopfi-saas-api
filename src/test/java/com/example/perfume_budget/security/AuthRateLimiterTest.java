package com.example.perfume_budget.security;

import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AuthRateLimiter limiter() {
        return new AuthRateLimiter(5, 3600, 2, 3600, 100);
    }

    @Test
    void perEmail_blocksTheThirdRequest_caseAndWhitespaceInsensitive_withRetryAfter() {
        AuthRateLimiter l = limiter();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        l.checkResendOtp("1.1.1.1", "a@x.test");
        l.checkResendOtp("1.1.1.2", " A@X.test ");
        assertThatThrownBy(() -> l.checkResendOtp("1.1.1.3", "a@x.TEST"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.getRetryAfterSeconds()).isPositive());
    }

    @Test
    void perEmail_isScopedPerTenant() {
        AuthRateLimiter l = limiter();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        l.checkResendOtp("1.1.1.1", "a@x.test");
        l.checkResendOtp("1.1.1.1", "a@x.test");
        TenantContext.set(TenantIdentity.tenant("bravo", "tenant_bravo"));
        l.checkResendOtp("1.1.1.2", "a@x.test"); // other tenant, own budget
    }

    @Test
    void perIp_blocksTheSixthRequestAcrossDifferentEmails() {
        AuthRateLimiter l = limiter();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        for (int i = 0; i < 5; i++) {
            l.checkResendOtp("9.9.9.9", "u" + i + "@x.test");
        }
        assertThatThrownBy(() -> l.checkResendOtp("9.9.9.9", "u99@x.test")).isInstanceOf(RateLimitExceededException.class);
        l.checkResendOtp("9.9.9.8", "u99@x.test"); // another IP is unaffected
    }
}
