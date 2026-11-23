package com.example.perfume_budget.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMetricsLikePatternTest {

    @Test
    void escapesPercentUnderscoreAndBackslashAndLowercases() {
        assertThat(CustomerMetricsQueryRepository.likePattern("50%_Off\\X")).isEqualTo("%50\\%\\_off\\\\x%");
    }

    @Test
    void plainTextIsOnlyWrapped() {
        assertThat(CustomerMetricsQueryRepository.likePattern("Ama")).isEqualTo("%ama%");
    }
}
