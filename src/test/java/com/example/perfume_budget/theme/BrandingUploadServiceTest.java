package com.example.perfume_budget.theme;

import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** THEME-02 AC2: sniffing, size limits, per-tenant folder, no tenant = failure. Fake storage, no network. */
class BrandingUploadServiceTest {

    FakeBrandingStorage storage = new FakeBrandingStorage("test");
    BrandingUploadService service = new BrandingUploadService(storage);

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static BrandingFile file(byte[] b, String type) {
        return new BrandingFile(b, type, "evil.svg");
    }

    private static void expectCode(Runnable r, String path, String code) {
        assertThatThrownBy(r::run).isInstanceOf(ThemeValidationException.class).satisfies(t -> {
            var e = ((ThemeValidationException) t).getErrors().get(0);
            assertThat(e.path()).isEqualTo(path);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    void acceptsPngJpegWebpAndStoresInTenantBrandingFolder() {
        String logo = service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(100, 100), "image/png")).url();
        String bg = service.upload(BrandingUploadService.Kind.BACKGROUND, null, file(FakeBrandingStorage.jpeg(800, 600), "image/jpeg")).url();
        String hero = service.upload(BrandingUploadService.Kind.SECTION_IMAGE, "hero", file(FakeBrandingStorage.webpLossy(640, 480), "image/webp")).url();
        assertThat(storage.uploads).hasSize(3);
        assertThat(storage.uploads.get(0)).matches("shopfi/acme/branding/logo-[0-9a-f]{16}");
        assertThat(storage.uploads.get(1)).matches("shopfi/acme/branding/background-[0-9a-f]{16}");
        assertThat(storage.uploads.get(2)).matches("shopfi/acme/branding/section-hero-[0-9a-f]{16}");
        assertThat(logo).startsWith("https://res.cloudinary.com/test/image/upload/");
        assertThat(bg).contains("shopfi/acme/branding/background");
        assertThat(hero).contains("section-hero");
    }

    @Test
    void contentTypeParametersAndCaseAreTolerated() {
        service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(10, 10), "IMAGE/PNG; charset=binary"));
        assertThat(storage.uploads).hasSize(1);
    }

    @Test
    void svgIsRejectedWhateverTheDeclaredType() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();
        for (String type : new String[]{"image/svg+xml", "image/png", "image/jpeg", "text/plain", null}) {
            expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(svg, type)), "logo", "FILE_TYPE_NOT_ALLOWED");
        }
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void spoofedContentTypeIsRejected() {
        // PNG bytes claiming JPEG, JPEG claiming PNG, HTML claiming PNG, PNG claiming nothing
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(5, 5), "image/jpeg")), "logo", "FILE_TYPE_NOT_ALLOWED");
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.jpeg(5, 5), "image/png")), "logo", "FILE_TYPE_NOT_ALLOWED");
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file("<html><script>x</script></html>".getBytes(), "image/png")), "logo", "FILE_TYPE_NOT_ALLOWED");
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(5, 5), null)), "logo", "FILE_TYPE_NOT_ALLOWED");
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void badMagicBytesAndTruncatedHeadersAreRejected() {
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000".getBytes();
        byte[] pdf = "%PDF-1.7 ....................................".getBytes();
        byte[] truncatedPng = Arrays.copyOf(FakeBrandingStorage.png(5, 5), 12);
        byte[] polyglot = ("\u0089PNG\r\n\u001a\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1); // signature only
        for (byte[] b : new byte[][]{gif, pdf, truncatedPng, polyglot, new byte[]{1, 2, 3}, "RIFF....WAVEfmt ....................".getBytes()}) {
            expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(b, "image/png")), "logo", "FILE_TYPE_NOT_ALLOWED");
        }
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(new byte[0], "image/png")), "logo", "FILE_TYPE_NOT_ALLOWED");
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, null), "logo", "FILE_TYPE_NOT_ALLOWED");
    }

    @Test
    void sizeLimitsPerKindThrowPayloadTooLarge() {
        byte[] bigLogo = Arrays.copyOf(FakeBrandingStorage.png(10, 10), (int) BrandingUploadService.LOGO_MAX_BYTES + 1);
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(bigLogo, "image/png")))
                .isInstanceOf(PayloadTooLargeException.class);
        // exactly at the limit is fine for a logo; 2 MB + 1 is fine for a background (limit 5 MB) but 5 MB + 1 is not
        byte[] atLimit = Arrays.copyOf(FakeBrandingStorage.png(10, 10), (int) BrandingUploadService.LOGO_MAX_BYTES);
        service.upload(BrandingUploadService.Kind.LOGO, null, file(atLimit, "image/png"));
        service.upload(BrandingUploadService.Kind.BACKGROUND, null, file(bigLogo, "image/png"));
        byte[] bigBg = Arrays.copyOf(FakeBrandingStorage.png(10, 10), (int) BrandingUploadService.BACKGROUND_MAX_BYTES + 1);
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.BACKGROUND, null, file(bigBg, "image/png")))
                .isInstanceOf(PayloadTooLargeException.class);
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.SECTION_IMAGE, "hero", file(bigBg, "image/png")))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void oversizedDimensionsAreRejected() {
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(4097, 10), "image/png")), "logo", "OUT_OF_RANGE");
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.jpeg(10, 5000), "image/jpeg")), "logo", "OUT_OF_RANGE");
        expectCode(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(0, 10), "image/png")), "logo", "FILE_TYPE_NOT_ALLOWED");
        service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(4096, 4096), "image/png"));
    }

    @Test
    void filenameNeverInfluencesStoragePath() {
        service.upload(BrandingUploadService.Kind.LOGO, null,
                new BrandingFile(FakeBrandingStorage.png(5, 5), "image/png", "../../bravo/branding/logo.svg"));
        assertThat(storage.uploads).singleElement().asString().matches("shopfi/acme/branding/logo-[0-9a-f]{16}");
    }

    @Test
    void sectionIdIsValidatedBeforeUse() {
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.SECTION_IMAGE, "../x", file(FakeBrandingStorage.png(5, 5), "image/png")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void failsWithoutATenant() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(5, 5), "image/png")))
                .isInstanceOf(IllegalStateException.class);
        TenantContext.set(TenantIdentity.platform());
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(5, 5), "image/png")))
                .isInstanceOf(IllegalStateException.class);
        TenantContext.set(TenantIdentity.template());
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(5, 5), "image/png")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void maliciousSlugIsNeverUsedAsFolder() {
        TenantContext.set(TenantIdentity.tenant("../bravo", "tenant_x"));
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(5, 5), "image/png")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void storageFailurePropagatesAsBrandingStorageException() {
        storage.failUploads = true;
        assertThatThrownBy(() -> service.upload(BrandingUploadService.Kind.LOGO, null, file(FakeBrandingStorage.png(5, 5), "image/png")))
                .isInstanceOf(BrandingStorageException.class);
    }
}
