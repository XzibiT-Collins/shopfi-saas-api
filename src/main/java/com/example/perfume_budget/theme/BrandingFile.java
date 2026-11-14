package com.example.perfume_budget.theme;

/**
 * An uploaded image as received by the web layer. {@code declaredContentType} is what the client claimed; it is
 * only compared against the sniffed type (never trusted). {@code filename} is ignored entirely.
 */
public record BrandingFile(byte[] bytes, String declaredContentType, String filename) {
}
