package com.example.perfume_budget.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ORD-07: parsing and arithmetic of the optional return window. */
class OrderReturnPolicyTest {

    private static final LocalDateTime DELIVERED = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Test
    void emptyBlankAndZeroMeanNoWindow() {
        for (String v : new String[]{null, "", "  ", "0", " 0 "}) {
            OrderReturnPolicy p = new OrderReturnPolicy(v);
            assertThat(p.windowDays()).as("[" + v + "]").isNull();
            assertThat(p.deadline(DELIVERED)).isNull();
            assertThat(p.isExpired(DELIVERED, DELIVERED.plusYears(50))).isFalse();
        }
    }

    @Test
    void aPositiveWindowCountsFromTheDeliveryAndTheDeadlineItselfIsStillInside() {
        OrderReturnPolicy p = new OrderReturnPolicy("7");
        assertThat(p.windowDays()).isEqualTo(7);
        assertThat(p.deadline(DELIVERED)).isEqualTo(DELIVERED.plusDays(7));
        assertThat(p.isExpired(DELIVERED, DELIVERED.plusDays(7))).isFalse();
        assertThat(p.isExpired(DELIVERED, DELIVERED.plusDays(7).plusSeconds(1))).isTrue();
        assertThat(p.isExpired(DELIVERED, DELIVERED.plusDays(1))).isFalse();
    }

    @Test
    void anUnknownDeliveryTimeHasNoDeadline() {
        OrderReturnPolicy p = new OrderReturnPolicy("7");
        assertThat(p.deadline(null)).isNull();
        assertThat(p.isExpired(null, LocalDateTime.now())).isFalse();
    }

    @Test
    void negativeOrNonNumericValuesStopTheApplication() {
        assertThatThrownBy(() -> new OrderReturnPolicy("-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OrderReturnPolicy("soon")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OrderReturnPolicy("1.5")).isInstanceOf(IllegalStateException.class);
    }
}
