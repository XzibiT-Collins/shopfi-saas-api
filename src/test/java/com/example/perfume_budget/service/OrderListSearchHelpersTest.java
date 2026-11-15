package com.example.perfume_budget.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderListSearchHelpersTest {
    @Test
    void likeWildcardsAndEscapeCharacterAreEscaped() {
        assertThat(OrderServiceImpl.likePattern("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
        assertThat(OrderServiceImpl.likePattern("plain")).isEqualTo("%plain%");
    }
}
