package com.example.perfume_budget.utils;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class ProductCodeGenerator {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ProductCodeGenerator() {
        throw new IllegalStateException("Utility class");
    }

    public static String generateFamilyCode(String name, String brand, String size) {
        String baseLabel = hasText(brand) ? brand : name;
        if (!hasText(baseLabel)) {
            throw new IllegalArgumentException("A product name or brand is required to generate a family code.");
        }

        StringBuilder code = new StringBuilder(buildBrandCode(baseLabel))
                .append("-")
                .append(String.format(Locale.ROOT, "%03d", ThreadLocalRandom.current().nextInt(1000)));

        if (hasText(size)) {
            code.append("-").append(normalizeSize(size));
        }

        return code.toString();
    }

    public static String generateConversionNumber(String prefix) {
        if (!hasText(prefix)) {
            throw new IllegalArgumentException("A conversion prefix is required.");
        }
        return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String buildBrandCode(String label) {
        String[] words = WHITESPACE.split(label.trim());
        if (words.length >= 2) {
            return words[0].substring(0, 1).toUpperCase(Locale.ROOT)
                    + words[1].substring(0, Math.min(words[1].length(), 2)).toUpperCase(Locale.ROOT);
        }
        return words[0].substring(0, Math.min(words[0].length(), 3)).toUpperCase(Locale.ROOT);
    }

    private static String normalizeSize(String size) {
        return WHITESPACE.matcher(size.trim()).replaceAll("").toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
