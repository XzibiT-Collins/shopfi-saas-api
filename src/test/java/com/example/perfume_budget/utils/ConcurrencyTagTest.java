package com.example.perfume_budget.utils;

import com.example.perfume_budget.exception.ApiCodeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConcurrencyTagTest {

    @Test
    void tokenIsStableAndChangesWithTheState() {
        assertEquals(ConcurrencyTag.of("a|b"), ConcurrencyTag.of("a|b"));
        assertNotEquals(ConcurrencyTag.of("a|b"), ConcurrencyTag.of("a|c"));
        assertEquals(32, ConcurrencyTag.of("x").length());
        assertEquals("\"" + ConcurrencyTag.of("x") + "\"", ConcurrencyTag.header(ConcurrencyTag.of("x")));
    }

    @Test
    void parsesQuotedWeakAndBareAndTreatsBlankAndWildcardAsNoPrecondition() {
        String t = ConcurrencyTag.of("x");
        assertEquals(t, ConcurrencyTag.parseIfMatch("\"" + t + "\""));
        assertEquals(t, ConcurrencyTag.parseIfMatch("W/\"" + t + "\""));
        assertEquals(t, ConcurrencyTag.parseIfMatch(" " + t + " "));
        assertNull(ConcurrencyTag.parseIfMatch(null));
        assertNull(ConcurrencyTag.parseIfMatch("  "));
        assertNull(ConcurrencyTag.parseIfMatch("*"));
    }

    @Test
    void junkAndListsAreInvalidIfMatch() {
        for (String bad : new String[]{"3", "abc", "\"" + "z".repeat(32) + "\"", ConcurrencyTag.of("x") + "," + ConcurrencyTag.of("y")}) {
            ApiCodeException e = assertThrows(ApiCodeException.class, () -> ConcurrencyTag.parseIfMatch(bad), bad);
            assertEquals("INVALID_IF_MATCH", e.getCode());
        }
    }

    @Test
    void requireOnlyFailsWhenAPreconditionWasSentAndDiffers() {
        ConcurrencyTag.require(null, "cur", "C", "m");
        ConcurrencyTag.require("cur", "cur", "C", "m");
        ApiCodeException e = assertThrows(ApiCodeException.class, () -> ConcurrencyTag.require("old", "cur", "C", "m"));
        assertEquals("C", e.getCode());
        assertEquals(409, e.getStatus().value());
        assertEquals("cur", e.body().get("currentVersion"));
    }
}
