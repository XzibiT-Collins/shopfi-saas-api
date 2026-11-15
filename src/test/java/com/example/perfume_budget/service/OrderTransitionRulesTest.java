package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import org.junit.jupiter.api.Test;

import static com.example.perfume_budget.enums.OrderProcessingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderTransitionRulesTest {

    @Test
    void everyPairMatchesTheDocumentedGraph() {
        for (OrderProcessingStatus from : values()) {
            for (OrderProcessingStatus to : values()) {
                boolean expected = from.ordinal() < DELIVERED.ordinal() && from != CANCELLED
                        && (to == CANCELLED || (to != PENDING && to.ordinal() > from.ordinal() && to != CANCELLED));
                if (from == to || from == DELIVERED || from == CANCELLED) expected = false;
                assertThat(OrderTransitionRules.isAllowed(from, to)).as(from + "->" + to).isEqualTo(expected);
            }
        }
    }

    @Test
    void finalStatesAreFinal() {
        assertThat(OrderTransitionRules.isFinal(DELIVERED)).isTrue();
        assertThat(OrderTransitionRules.isFinal(CANCELLED)).isTrue();
        assertThat(OrderTransitionRules.isFinal(PENDING)).isFalse();
        assertThat(OrderTransitionRules.next(DELIVERED)).isEmpty();
        assertThat(OrderTransitionRules.next(CANCELLED)).isEmpty();
        assertThat(OrderTransitionRules.next(PACKING)).containsExactlyInAnyOrder(OUT_FOR_DELIVERY, DELIVERED, CANCELLED);
    }
}
