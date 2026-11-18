package com.example.perfume_budget.utils;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class ProductCodeGenerator {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ProductCodeGenerator() {
        throw new IllegalStateException("Utility class");
    }

    /** A family code with the default 3-digit random suffix: {@code <prefix>-<ddd>[-<SIZE>]}. */
    public static String generateFamilyCode(String name, String brand, String size) {
        return generateFamilyCode(name, brand, size, 3);
    }

    /**
     * ORD-02c: {@code <prefix>-<suffixDigits random digits, zero-padded>[-<SIZE>]}. The prefix is three letters of the
     * brand (or the name); the suffix width is 3 by default and 4 for the fallback when the 3-digit space is crowded.
     */
    public static String generateFamilyCode(String name, String brand, String size, int suffixDigits) {
        if (suffixDigits < 1 || suffixDigits > 9) {
            throw new IllegalArgumentException("Unsupported family code suffix width.");
        }
        String baseLabel = hasText(brand) ? brand : name;
        if (!hasText(baseLabel)) {
            throw new IllegalArgumentException("A product name or brand is required to generate a family code.");
        }
        int bound = (int) Math.pow(10, suffixDigits);
        StringBuilder code = new StringBuilder(buildBrandCode(baseLabel))
                .append("-")
                .append(String.format(Locale.ROOT, "%0" + suffixDigits + "d", ThreadLocalRandom.current().nextInt(bound)));

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
