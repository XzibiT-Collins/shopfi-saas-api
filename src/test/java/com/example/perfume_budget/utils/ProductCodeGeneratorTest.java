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
