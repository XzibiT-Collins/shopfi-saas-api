package com.example.perfume_budget.theme;

import com.fasterxml.jackson.databind.JsonNode;

public interface ThemeService {

    /**
     * The active tenant's theme (cached per tenant), always with {@code themeId}: a stored document without one is
     * resolved in memory (never written), see {@link ThemeCanonicalizer#resolveStored}. Creates the default row if the
     * singleton row is missing.
     */
    ThemeSnapshot getActiveTheme();

    /**
     * Canonicalizes (SF-BE-01: tokens from the catalog theme, hero-only pages, no background; see
     * {@link ThemeCanonicalizer}), validates and stores {@code theme} (full replacement), uploading any images first.
     * The returned snapshot is the canonical document as stored.
     *
     * @param expectedVersion entity version from the last GET's ETag (If-Match), or {@code null} for last-write-wins
     * @param uploads         optional image parts, never {@code null} (use {@link ThemeUploads#NONE})
     * @param updatedBy       audit identity, may be {@code null}
     * @throws ThemeValidationException   400 with field errors (an unknown or reserved {@code themeId} is {@code NOT_ALLOWED_VALUE})
     * @throws PayloadTooLargeException   413
     * @throws ThemeConflictException     412 (stale version)
     * @throws BrandingStorageException   502 (nothing persisted)
     */
    ThemeSnapshot updateTheme(JsonNode theme, Long expectedVersion, ThemeUploads uploads, String updatedBy);

    default ThemeSnapshot updateTheme(JsonNode theme, Long expectedVersion) {
        return updateTheme(theme, expectedVersion, ThemeUploads.NONE, null);
    }
}
