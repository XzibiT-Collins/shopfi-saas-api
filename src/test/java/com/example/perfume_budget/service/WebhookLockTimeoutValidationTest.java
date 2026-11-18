package com.example.perfume_budget.service;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * EVT-01d (5): the webhook settlement budget is validated when the application starts. At or below 250 ms no attempt
 * would ever start (every settlement webhook answered 500 forever); at or above Paystack's 30 s delivery timeout the
 * provider gives up on deliveries we may still commit. Either refuses to start, with a message naming the property.
 */
class WebhookLockTimeoutValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // the bean's @PersistenceContext needs a factory to exist; it is never used here
            .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
            .withBean(WebhookLockTimeout.class);

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1, 249, 250, 30_000, 30_001, 60_000, Long.MAX_VALUE})
    void anUnworkableDeadlineIsRefused(long deadline) {
        assertThatThrownBy(() -> WebhookLockTimeout.validate(8000, deadline))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shopfi.webhook.settlement-deadline-ms")
                .hasMessageContaining("more than 250 ms")
                .hasMessageContaining("less than 30000 ms")
                .hasMessageContaining(" is " + deadline + " ms");
    }

    @ParameterizedTest
    @ValueSource(longs = {251, 1000, 20_000, 29_999})
    void aWorkableDeadlineIsAccepted(long deadline) {
        assertThatCode(() -> WebhookLockTimeout.validate(8000, deadline)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void aLockTimeoutBelow1msIsRefused(long lockTimeout) {
        assertThatThrownBy(() -> WebhookLockTimeout.validate(lockTimeout, 20_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shopfi.webhook.lock-timeout-ms");
        assertThatCode(() -> WebhookLockTimeout.validate(1, 20_000)).doesNotThrowAnyException();
    }

    @Test
    void theApplicationFailsToStartWithTheMessage_onATooSmallDeadline() {
        runner.withPropertyValues("shopfi.webhook.settlement-deadline-ms=250").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shopfi.webhook.settlement-deadline-ms")
                    .hasMessageContaining("is 250 ms");
        });
    }

    @Test
    void theApplicationFailsToStartWithTheMessage_onADeadlineAtPaystacksTimeout() {
        runner.withPropertyValues("shopfi.webhook.settlement-deadline-ms=30000").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("is 30000 ms");
        });
    }

    @Test
    void theApplicationStartsWithTheDefaults_andWithAWorkableOverride() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(WebhookLockTimeout.class).deadlineMillis()).isEqualTo(20_000L);
            assertThat(ctx.getBean(WebhookLockTimeout.class).timeoutMillis()).isEqualTo(8_000L);
        });
        runner.withPropertyValues("shopfi.webhook.settlement-deadline-ms=5000").run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
