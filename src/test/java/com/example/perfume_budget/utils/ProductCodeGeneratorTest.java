package com.example.perfume_budget.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCodeGeneratorTest {

    @Test
    void generateFamilyCode_UsesBrandAndNormalizedSize() {
        String code = ProductCodeGenerator.generateFamilyCode("Perfume A", "Vanilla Spice", "50 ml");

        assertTrue(code.startsWith("VSP-"));
        assertTrue(code.endsWith("-50ML"));
    }

    @Test
    void generateFamilyCode_ThreeDigitSuffixByDefaultAndFourWhenAsked() {
        for (int i = 0; i < 200; i++) {
            assertTrue(ProductCodeGenerator.generateFamilyCode("Perfume A", "Vanilla Spice", "50 ml").matches("VSP-\\d{3}-50ML"));
            assertTrue(ProductCodeGenerator.generateFamilyCode("Perfume A", "Vanilla Spice", "50 ml", 3).matches("VSP-\\d{3}-50ML"));
            assertTrue(ProductCodeGenerator.generateFamilyCode("Perfume A", "Vanilla Spice", "50 ml", 4).matches("VSP-\\d{4}-50ML"));
            assertTrue(ProductCodeGenerator.generateFamilyCode("Oud", null, null, 4).matches("OUD-\\d{4}"));
        }
    }

    @Test
    void generateFamilyCode_FourDigitSuffixCoversTheWholeRangeIncludingLeadingZeros() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200_000 && seen.size() < 10_000; i++) {
            seen.add(ProductCodeGenerator.generateFamilyCode("Oud", null, null, 4));
        }
        assertEquals(10_000, seen.size());
        assertTrue(seen.contains("OUD-0000"));
        assertTrue(seen.contains("OUD-9999"));
    }

    @Test
    void generateFamilyCode_RejectsAnUnsupportedSuffixWidth() {
        assertThrows(IllegalArgumentException.class, () -> ProductCodeGenerator.generateFamilyCode("Oud", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> ProductCodeGenerator.generateFamilyCode("Oud", null, null, 10));
    }

    @Test
    void generateConversionNumber_UsesPrefix() {
        String conversionNumber = ProductCodeGenerator.generateConversionNumber("CONV-FWD");

        assertTrue(conversionNumber.startsWith("CONV-FWD-"));
        assertEquals(17, conversionNumber.length());
    }

    @Test
    void generateFamilyCode_WithoutNameOrBrand_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ProductCodeGenerator.generateFamilyCode(" ", null, "50ml"));
    }
}
