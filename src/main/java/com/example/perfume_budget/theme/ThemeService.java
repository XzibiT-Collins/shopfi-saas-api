package com.example.perfume_budget.theme;

import com.fasterxml.jackson.databind.JsonNode;

public interface ThemeService {

    /**
     * The active tenant's theme (cached per tenant). Creates the default row if the singleton row is missing.
     */
    ThemeSnapshot getActiveTheme();

    /**
     * Validates and stores {@code theme} (full replacement), uploading any images first.
     *
     * @param expectedVersion entity version from the last GET's ETag (If-Match), or {@code null} for last-write-wins
     * @param uploads         optional image parts, never {@code null} (use {@link ThemeUploads#NONE})
     * @param updatedBy       audit identity, may be {@code null}
     * @throws ThemeValidationException   400 with field errors
     * @throws PayloadTooLargeException   413
     * @throws ThemeConflictException     412 (stale version)
     * @throws BrandingStorageException   502 (nothing persisted)
     */
    ThemeSnapshot updateTheme(JsonNode theme, Long expectedVersion, ThemeUploads uploads, String updatedBy);

    default ThemeSnapshot updateTheme(JsonNode theme, Long expectedVersion) {
        return updateTheme(theme, expectedVersion, ThemeUploads.NONE, null);
    }
}
