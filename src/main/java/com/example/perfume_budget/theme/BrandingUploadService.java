package com.example.perfume_budget.theme;

import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates and stores tenant branding images. The type is decided by sniffing magic bytes (PNG/JPEG/WebP
 * only); the client's Content-Type must agree but is never trusted and the filename is ignored. The Cloudinary
 * folder is {@code shopfi/<tenantSlug>/branding} with the slug taken from {@link TenantContext}, never from
 * input; if no tenant is bound the call fails.
 */
@Service
@RequiredArgsConstructor
public class BrandingUploadService {

    public static final int MAX_DIMENSION_PX = 4096;
    public static final long LOGO_MAX_BYTES = 2L * 1024 * 1024;
    public static final long BACKGROUND_MAX_BYTES = 5L * 1024 * 1024;
    public static final long SECTION_IMAGE_MAX_BYTES = 5L * 1024 * 1024;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$");
    private static final Pattern SECTION_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

    public enum Kind {
        LOGO(LOGO_MAX_BYTES),
        BACKGROUND(BACKGROUND_MAX_BYTES),
        SECTION_IMAGE(SECTION_IMAGE_MAX_BYTES);

        final long maxBytes;

        Kind(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    private final BrandingStorage storage;

    /** Slug of the bound tenant. @throws IllegalStateException when no (valid) tenant is bound */
    public static String currentSlug() {
        String slug = TenantContext.getSlug();
        var identity = TenantContext.get();
        if (identity == null || identity.scope() != TenantScope.TENANT || slug == null || !SLUG.matcher(slug).matches()) {
            throw new IllegalStateException("Branding upload requires a resolved tenant");
        }
        return slug;
    }

    /** Folder for the bound tenant. @throws IllegalStateException when no (valid) tenant is bound */
    public static String brandingFolder() {
        return "shopfi/" + currentSlug() + "/branding";
    }

    /** Fixed public id (no client-controlled characters). */
    public static String publicId(Kind kind, String sectionId) {
        return switch (kind) {
            case LOGO -> "logo";
            case BACKGROUND -> "background";
            case SECTION_IMAGE -> {
                if (sectionId == null || !SECTION_ID.matcher(sectionId).matches()) {
                    throw new IllegalArgumentException("Invalid section id");
                }
                yield "section-" + sectionId;
            }
        };
    }

    /**
     * Pure validation (no network): size, magic bytes, declared-type agreement, dimensions.
     *
     * @param path field path used in error reports, e.g. {@code logo} or {@code image.hero}
     */
    public void validate(Kind kind, BrandingFile file, String path) {
        if (file == null || file.bytes() == null || file.bytes().length == 0) {
            throw bad(path, "FILE_TYPE_NOT_ALLOWED", "file is empty");
        }
        if (file.bytes().length > kind.maxBytes) {
            throw new PayloadTooLargeException(path + " exceeds " + (kind.maxBytes / (1024 * 1024)) + " MB");
        }
        ImageSniffer.Info info = ImageSniffer.sniff(file.bytes());
        if (info == null) {
            throw bad(path, "FILE_TYPE_NOT_ALLOWED", "only PNG, JPEG and WebP images are accepted");
        }
        String declared = file.declaredContentType() == null ? "" : file.declaredContentType().trim().toLowerCase();
        int semi = declared.indexOf(';');
        if (semi >= 0) {
            declared = declared.substring(0, semi).trim();
        }
        if (!declared.equals(info.type().mime)) {
            throw bad(path, "FILE_TYPE_NOT_ALLOWED", "declared content type does not match the file content");
        }
        if (info.width() > MAX_DIMENSION_PX || info.height() > MAX_DIMENSION_PX) {
            throw bad(path, "OUT_OF_RANGE", "image must be at most " + MAX_DIMENSION_PX + "x" + MAX_DIMENSION_PX + " pixels");
        }
    }

    /** Validates then uploads; returns the secure URL. */
    public String upload(Kind kind, String sectionId, BrandingFile file) {
        String path = switch (kind) {
            case LOGO -> "logo";
            case BACKGROUND -> "background";
            case SECTION_IMAGE -> "image." + sectionId;
        };
        String folder = brandingFolder();
        String name = publicId(kind, sectionId);
        validate(kind, file, path);
        return storage.upload(file.bytes(), folder, name);
    }

    /** Best-effort cleanup of an asset this service created. */
    public void delete(String name) {
        storage.delete(brandingFolder(), name);
    }

    private static ThemeValidationException bad(String path, String code, String message) {
        return new ThemeValidationException(List.of(new ThemeFieldError(path, code, message)));
    }
}
