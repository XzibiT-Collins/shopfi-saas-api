package com.example.perfume_budget.tenancy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** TENANT-10 AC2: header resolution enabled outside dev/test must abort startup. */
class TenantHeaderResolutionGuardTest {

    @Configuration
    @EnableConfigurationProperties(TenantResolutionProperties.class)
    static class Cfg {
        @Bean
        TenantHeaderResolutionGuard guard(TenantResolutionProperties p, org.springframework.core.env.Environment e) {
            return new TenantHeaderResolutionGuard(p, e);
        }
    }

    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Cfg.class)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void enabledWithNoProfile_failsStartup() {
        runner().withPropertyValues("tenant.header-resolution.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void enabledWithProd_failsStartup() {
        runner("prod").withPropertyValues("tenant.header-resolution.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void enabledWithArbitraryProfile_failsStartup() {
        runner("staging").withPropertyValues("tenant.header-resolution.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void enabledWithDevOrTestProfiles_starts() {
        for (String p : new String[]{"dev", "test", "pgtest"}) {
            runner(p).withPropertyValues("tenant.header-resolution.enabled=true")
                    .run(ctx -> assertThat(ctx).hasNotFailed());
        }
    }

    @Test
    void disabledStartsInAnyProfile() {
        runner("prod").run(ctx -> assertThat(ctx).hasNotFailed());
        runner().run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
