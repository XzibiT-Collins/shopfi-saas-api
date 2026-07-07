package com.example.perfume_budget.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlugGeneratorTest {

    @Test
    void generateNormalSlug_Success() {
        assertEquals("test-name", SlugGenerator.generateNormalSlug("Test Name"));
        assertEquals("perfume-a-123", SlugGenerator.generateNormalSlug("  Perfume A 123  "));
        assertEquals("special-chars", SlugGenerator.generateNormalSlug("Special! @Chars#"));
        assertEquals("multiple-dashes", SlugGenerator.generateNormalSlug("Multiple---Dashes"));
    }

    @Test
    void generateSlug_Success() {
        String name = "Test Perfume";
        String slug = SlugGenerator.generateSlug(name);
        
        assertTrue(slug.startsWith("test-perfume"));
        assertTrue(slug.length() > "test-perfume".length());
        // Should have 8 extra chars (UUID substring)
        assertEquals("test-perfume".length() + 8, slug.length()); 
    }
}
