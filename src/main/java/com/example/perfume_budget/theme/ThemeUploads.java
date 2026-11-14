package com.example.perfume_budget.theme;

import java.util.Map;

/** Optional image parts of a PUT: {@code logo}, {@code background} and {@code image.<sectionId>} (hero). */
public record ThemeUploads(BrandingFile logo, BrandingFile background, Map<String, BrandingFile> sectionImages) {

    public static final ThemeUploads NONE = new ThemeUploads(null, null, Map.of());

    public ThemeUploads {
        sectionImages = sectionImages == null ? Map.of() : Map.copyOf(sectionImages);
    }

    public boolean isEmpty() {
        return logo == null && background == null && sectionImages.isEmpty();
    }
}
