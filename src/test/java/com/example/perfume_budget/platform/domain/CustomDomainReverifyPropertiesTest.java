package com.example.perfume_budget.platform.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DOMAIN-03 L10: startup validation of tenant.custom-domain.reverify.*. */
class CustomDomainReverifyPropertiesTest {

    private static CustomDomainReverifyProperties with(Consumer<CustomDomainReverifyProperties> c) {
        CustomDomainReverifyProperties p = new CustomDomainReverifyProperties();
        c.accept(p);
        return p;
    }

    @Test
    void defaultsAreValid() {
        assertThatCode(() -> new CustomDomainReverifyProperties().afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void zeroAndNegativeValuesAreRejectedWithTheirName() {
        assertThatThrownBy(() -> with(p -> p.setTickInterval(Duration.ZERO)).afterPropertiesSet()).hasMessageContaining("tick-interval");
        assertThatThrownBy(() -> with(p -> p.setInterval(Duration.ofHours(-1))).afterPropertiesSet()).hasMessageContaining(".interval");
        assertThatThrownBy(() -> with(p -> p.setMissRetryInterval(Duration.ZERO)).afterPropertiesSet()).hasMessageContaining("miss-retry-interval");
        assertThatThrownBy(() -> with(p -> p.setErrorRetryInterval(Duration.ZERO)).afterPropertiesSet()).hasMessageContaining("error-retry-interval");
        assertThatThrownBy(() -> with(p -> p.setLease(Duration.ZERO)).afterPropertiesSet()).hasMessageContaining("lease");
        assertThatThrownBy(() -> with(p -> p.setGrace(Duration.ofMinutes(-1))).afterPropertiesSet()).hasMessageContaining("grace");
        assertThatThrownBy(() -> with(p -> p.setInitialDelay(Duration.ofMinutes(-1))).afterPropertiesSet()).hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> with(p -> p.setBatchSize(0)).afterPropertiesSet()).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> with(p -> p.setMissesRequired(0)).afterPropertiesSet()).hasMessageContaining("misses-required");
        assertThatThrownBy(() -> with(p -> p.setDnsPerMinute(0)).afterPropertiesSet()).hasMessageContaining("dns-per-minute");
        assertThatThrownBy(() -> with(p -> p.setJitterPercent(-1)).afterPropertiesSet()).hasMessageContaining("jitter-percent");
        assertThatThrownBy(() -> with(p -> p.setJitterPercent(101)).afterPropertiesSet()).hasMessageContaining("jitter-percent");
    }

    @Test
    void onlyAWarningForOneMissAndNoGrace() {
        Logger log = (Logger) LoggerFactory.getLogger(CustomDomainReverifyProperties.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        log.addAppender(logs);
        try {
            assertThatCode(() -> with(p -> {
                p.setMissesRequired(1);
                p.setGrace(Duration.ZERO);
            }).afterPropertiesSet()).doesNotThrowAnyException();
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("misses-required=1") && m.contains("grace=0"));
            logs.list.clear();
            new CustomDomainReverifyProperties().afterPropertiesSet();
            assertThat(logs.list).isEmpty();
        } finally {
            log.detachAppender(logs);
        }
    }
}
