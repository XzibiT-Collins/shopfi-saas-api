package com.example.perfume_budget.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** If-None-Match matching: weak comparison, lists, wildcard, garbage. */
class StoreThemeControllerMatchTest {

    @Test
    void matchesWeakStrongListAndWildcard() {
        assertThat(StoreThemeController.matches("W/\"3\"", 3)).isTrue();
        assertThat(StoreThemeController.matches("\"3\"", 3)).isTrue();
        assertThat(StoreThemeController.matches("W/\"2\", W/\"3\"", 3)).isTrue();
        assertThat(StoreThemeController.matches("*", 3)).isTrue();
        assertThat(StoreThemeController.matches("W/\"2\"", 3)).isFalse();
        assertThat(StoreThemeController.matches("garbage", 3)).isFalse();
        assertThat(StoreThemeController.matches("", 3)).isFalse();
        assertThat(StoreThemeController.matches(null, 3)).isFalse();
    }
}
