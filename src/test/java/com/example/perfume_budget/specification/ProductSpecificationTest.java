package com.example.perfume_budget.specification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** CAT-05: the LIKE pattern of the product search is literal text. */
class ProductSpecificationTest {

    @Test
    void plainTermIsWrappedLowerCasedAndTrimmed() {
        assertEquals("%oud royal%", ProductSpecification.likePattern("  Oud Royal \t"));
    }

    @Test
    void wildcardsAndTheEscapeCharacterAreEscaped() {
        assertEquals("%50\\%%", ProductSpecification.likePattern("50%"));
        assertEquals("%a\\_b%", ProductSpecification.likePattern("a_b"));
        assertEquals("%a\\\\b%", ProductSpecification.likePattern("a\\b"));
        assertEquals("%\\%\\_\\\\%", ProductSpecification.likePattern("%_\\"));
    }

    @Test
    void blankOrMissingTermMeansNoFilter() {
        assertNull(ProductSpecification.likePattern(null));
        assertNull(ProductSpecification.likePattern(""));
        assertNull(ProductSpecification.likePattern(" \t\n"));
    }

    @Test
    void escapeCharacterIsBackslash() {
        assertEquals('\\', ProductSpecification.LIKE_ESCAPE);
    }
}
