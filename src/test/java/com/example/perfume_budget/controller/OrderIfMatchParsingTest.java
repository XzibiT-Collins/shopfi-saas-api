package com.example.perfume_budget.controller;

import com.example.perfume_budget.exception.ApiCodeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIfMatchParsingTest {
    @Test
    void acceptsQuotedWeakAndBareVersions() {
        assertThat(OrderController.parseIfMatch("\"3\"")).isEqualTo(3L);
        assertThat(OrderController.parseIfMatch("W/\"12\"")).isEqualTo(12L);
        assertThat(OrderController.parseIfMatch(" 0 ")).isZero();
        assertThat(OrderController.parseIfMatch(null)).isNull();
        assertThat(OrderController.parseIfMatch("  ")).isNull();
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[]{"*", "abc", "\"-1\"", "1.5", "\"\"", "\"3", "9999999999999999999999"}) {
            assertThatThrownBy(() -> OrderController.parseIfMatch(bad)).as(bad).isInstanceOf(ApiCodeException.class)
                    .extracting(e -> ((ApiCodeException) e).getCode()).isEqualTo("INVALID_IF_MATCH");
        }
    }
}
