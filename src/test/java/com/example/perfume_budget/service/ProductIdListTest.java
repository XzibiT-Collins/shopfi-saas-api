package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CAT-07: strict parsing of the {@code ids} parameter of GET /product/by-ids. */
class ProductIdListTest {

    private static List<Long> ok(String... raw) {
        return ProductIdList.parse(raw);
    }

    private static void rejects(String message, String... raw) {
        assertThatThrownBy(() -> ProductIdList.parse(raw)).isInstanceOf(BadRequestException.class).hasMessage(message);
    }

    @Test
    void parsesCommaListsRepeatedParamsAndWhitespace_keepsFirstOccurrenceOrder_dropsRepeats() {
        assertThat(ok("3,1,2")).containsExactly(3L, 1L, 2L);
        assertThat(ok(" 5 , 6 ")).containsExactly(5L, 6L);
        assertThat(ok("7", "8,9")).containsExactly(7L, 8L, 9L);
        assertThat(ok("4,4,2,4")).containsExactly(4L, 2L);
        assertThat(ok("999999999999999999")).containsExactly(999999999999999999L);
    }

    @Test
    void twelveIsTheLimit_countedBeforeRemovingRepeats() {
        assertThat(ok("1,2,3,4,5,6,7,8,9,10,11,12")).hasSize(12);
        rejects("At most 12 ids are allowed", "1,2,3,4,5,6,7,8,9,10,11,12,13");
        rejects("At most 12 ids are allowed", "1,1,1,1,1,1,1,1,1,1,1,1,1");
        rejects("At most 12 ids are allowed", "1,2,3,4,5,6", "7,8,9,10,11,12,13");
        rejects("At most 12 ids are allowed", "1".repeat(1001));
    }

    @Test
    void missingOrEmptyIsRequired() {
        rejects("ids is required", (String[]) null);
        rejects("ids is required");
        rejects("ids is required", "");
        rejects("ids is required", "  ");
    }

    @Test
    void malformedEntriesAreRejected() {
        for (String bad : List.of("0", "-1", "+1", "1.5", "abc", "1,,2", "1,", ",1", "1;2", "01", "0x10", "1e3",
                "1234567890123456789", "９", "1 2")) {
            rejects("ids must be positive integers", bad);
        }
    }
}
