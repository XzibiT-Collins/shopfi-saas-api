package com.example.perfume_budget.theme;

import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.Map;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Orchestration order and atomicity: validate first, upload, persist, clean up. Mocked store, fake Cloudinary. */
class ThemeServiceUploadFlowTest {

    FakeBrandingStorage storage = new FakeBrandingStorage("shopfi-demo");
    ThemeStore store = mock(ThemeStore.class);
    ProductRepository products = mock(ProductRepository.class);
    CategoryRepository categories = mock(CategoryRepository.class);
    ThemeServiceImpl service;

    @BeforeEach
    void setUp() {
        when(products.findExistingIds(anyCollection())).thenAnswer(i -> new java.util.ArrayList<>((java.util.Collection<Long>) i.getArgument(0)));
        when(categories.findExistingIds(anyCollection())).thenAnswer(i -> new java.util.ArrayList<>((java.util.Collection<Long>) i.getArgument(0)));
        ThemeValidator validator = new ThemeValidator(MAPPER, products, categories, "shopfi-demo");
        service = new ThemeServiceImpl(store, validator, new BrandingUploadService(storage), new ConcurrentMapCacheManager(), MAPPER);
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        when(store.read()).thenReturn(new ThemeSnapshot(defaults().toString(), 1));
        when(store.save(any(), any(), any())).thenAnswer(i -> new ThemeSnapshot(i.getArgument(0).toString(), 2));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ObjectNode withLogoAlt() {
        ObjectNode d = defaults();
        d.with("assets").putObject("logo").put("alt", "Acme");
        return d;
    }

    @Test
    void invalidDocumentNeverReachesCloudinaryOrDatabase() {
        ObjectNode d = withLogoAlt();
        ((ObjectNode) section(d, "hero").get("props")).put("heading", "<script>alert(1)</script>");
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(10, 10), "image/png", "l.png"), null, Map.of());
        assertThatThrownBy(() -> service.updateTheme(d, null, up, "admin")).isInstanceOf(ThemeValidationException.class);
        assertThat(storage.uploads).isEmpty();
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void badFileNeverReachesCloudinaryOrDatabase() {
        ThemeUploads up = new ThemeUploads(new BrandingFile("<svg/>".getBytes(), "image/svg+xml", "l.svg"), null, Map.of());
        assertThatThrownBy(() -> service.updateTheme(withLogoAlt(), null, up, "admin"))
                .isInstanceOfSatisfying(ThemeValidationException.class,
                        e -> assertThat(e.getErrors()).extracting(ThemeFieldError::code).contains("FILE_TYPE_NOT_ALLOWED"));
        assertThat(storage.uploads).isEmpty();
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void logoUploadReplacesUrlInStoredDocumentWithSecureUrl() {
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(10, 10), "image/png", "x.png"), null, Map.of());
        ThemeSnapshot saved = service.updateTheme(withLogoAlt(), 1L, up, "admin");
        assertThat(storage.uploads).singleElement().asString().matches("shopfi/acme/branding/logo-[0-9a-f]{16}");
        assertThat(saved.theme().at("/assets/logo/url").asText())
                .matches("https://res\\.cloudinary\\.com/shopfi-demo/image/upload/v1/shopfi/acme/branding/logo-[0-9a-f]{16}\\.png");
        assertThat(saved.theme().at("/assets/logo/alt").asText()).isEqualTo("Acme");
        verify(store).save(any(), eq(1L), eq("admin"));
    }

    @Test
    void logoUploadWithoutAltIsRequiredError() {
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(10, 10), "image/png", "x.png"), null, Map.of());
        assertThatThrownBy(() -> service.updateTheme(defaults(), null, up, "a"))
                .isInstanceOfSatisfying(ThemeValidationException.class,
                        e -> assertThat(e.getErrors()).anyMatch(x -> x.path().equals("assets.logo.alt") && x.code().equals("REQUIRED")));
    }

    @Test
    void backgroundAndHeroImageUploads() {
        ThemeUploads up = new ThemeUploads(null,
                new BrandingFile(FakeBrandingStorage.jpeg(100, 100), "image/jpeg", "b.jpg"),
                Map.of("hero", new BrandingFile(FakeBrandingStorage.webpLossy(50, 50), "image/webp", "h.webp")));
        ThemeSnapshot saved = service.updateTheme(defaults(), null, up, "admin");
        assertThat(storage.uploads).hasSize(2).anyMatch(k -> k.matches("shopfi/acme/branding/background-[0-9a-f]{16}"))
                .anyMatch(k -> k.matches("shopfi/acme/branding/section-hero-[0-9a-f]{16}"));
        assertThat(saved.theme().at("/assets/background/url").asText()).contains("shopfi/acme/branding/background");
        assertThat(saved.theme().at("/pages/home/0/props/imageUrl").asText()).contains("section-hero");
    }

    @Test
    void sectionImageForUnknownOrNonHeroSectionIsRejected() {
        ThemeUploads up = new ThemeUploads(null, null,
                Map.of("featured", new BrandingFile(FakeBrandingStorage.png(5, 5), "image/png", "x.png"),
                        "nope", new BrandingFile(FakeBrandingStorage.png(5, 5), "image/png", "x.png")));
        assertThatThrownBy(() -> service.updateTheme(defaults(), null, up, "a"))
                .isInstanceOfSatisfying(ThemeValidationException.class,
                        e -> assertThat(e.getErrors()).extracting(ThemeFieldError::path).contains("image.featured", "image.nope"));
        assertThat(storage.uploads).isEmpty();
    }

    private static final String OLD_BG = "https://res.cloudinary.com/shopfi-demo/image/upload/v1/shopfi/acme/branding/background.png";

    @Test
    void persistenceFailureDeletesOnlyTheNewAssetsAndNeverTheLiveOnes() {
        // the live theme already points at background.png, which exists in the store
        storage.objects.put("shopfi/acme/branding/background", new byte[]{1, 2, 3});
        ObjectNode stored = defaults();
        stored.with("assets").putObject("background").put("url", OLD_BG);
        when(store.read()).thenReturn(new ThemeSnapshot(stored.toString(), 3));
        doThrow(new ThemeConflictException("stale")).when(store).save(any(), any(), any());
        ThemeUploads up = new ThemeUploads(null,
                new BrandingFile(FakeBrandingStorage.jpeg(100, 100), "image/jpeg", "b.jpg"), Map.of());

        assertThatThrownBy(() -> service.updateTheme(defaults(), 3L, up, "a")).isInstanceOf(ThemeConflictException.class);

        assertThat(storage.uploads).singleElement().asString().matches("shopfi/acme/branding/background-[0-9a-f]{16}");
        String newKey = storage.uploads.get(0);
        assertThat(storage.deletes).containsExactly(newKey);                    // the new asset is rolled back
        assertThat(storage.objects).containsKey("shopfi/acme/branding/background") // the live one is untouched
                .doesNotContainKey(newKey);
        assertThat(storage.objects.get("shopfi/acme/branding/background")).containsExactly(1, 2, 3);
        assertThat(storage.uploads).doesNotContain("shopfi/acme/branding/background"); // never overwritten
    }

    @Test
    void staleIfMatchIsRejectedBeforeAnyUploadOrAssetChange() {
        storage.objects.put("shopfi/acme/branding/background", new byte[]{1, 2, 3});
        ObjectNode stored = defaults();
        stored.with("assets").putObject("background").put("url", OLD_BG);
        when(store.read()).thenReturn(new ThemeSnapshot(stored.toString(), 5)); // current version 5
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(10, 10), "image/png", "l.png"),
                new BrandingFile(FakeBrandingStorage.jpeg(100, 100), "image/jpeg", "b.jpg"), Map.of());
        ObjectNode doc = withLogoAlt();

        assertThatThrownBy(() -> service.updateTheme(doc, 4L, up, "a")).isInstanceOf(ThemeConflictException.class);

        assertThat(storage.uploads).isEmpty();   // no Cloudinary call of any kind
        assertThat(storage.deletes).isEmpty();
        assertThat(storage.objects).containsOnlyKeys("shopfi/acme/branding/background");
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void everyUploadGetsAFreshPublicIdSoNothingIsOverwritten() {
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(10, 10), "image/png", "l.png"), null, Map.of());
        service.updateTheme(withLogoAlt(), null, up, "a");
        service.updateTheme(withLogoAlt(), null, up, "a");
        assertThat(storage.uploads).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void invalidSectionPartNameIsNeverEchoedInTheFieldErrorPath() {
        String hostile = "<script>" + "x".repeat(5000);
        ThemeUploads up = new ThemeUploads(null, null, Map.of(hostile, new BrandingFile(FakeBrandingStorage.png(5, 5), "image/png", "x.png")));
        assertThatThrownBy(() -> service.updateTheme(defaults(), null, up, "a"))
                .isInstanceOfSatisfying(ThemeValidationException.class, e -> {
                    assertThat(e.getErrors()).extracting(ThemeFieldError::path).containsExactly("image");
                    assertThat(e.getErrors().toString()).doesNotContain("script").hasSizeLessThan(500);
                });
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void storageOutageLeavesThemeUnchanged() {
        storage.failUploads = true;
        ThemeUploads up = new ThemeUploads(new BrandingFile(FakeBrandingStorage.png(10, 10), "image/png", "x.png"), null, Map.of());
        assertThatThrownBy(() -> service.updateTheme(withLogoAlt(), null, up, "a")).isInstanceOf(BrandingStorageException.class);
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void removedAssetIsDeletedBestEffortAfterSave() {
        ObjectNode stored = defaults();
        stored.with("assets").putObject("logo").put("url", "https://res.cloudinary.com/shopfi-demo/image/upload/v1/shopfi/acme/branding/logo.png").put("alt", "x");
        when(store.read()).thenReturn(new ThemeSnapshot(stored.toString(), 3));
        service.updateTheme(defaults(), 3L, ThemeUploads.NONE, "a"); // logo null in new doc
        assertThat(storage.deletes).containsExactly("shopfi/acme/branding/logo");
    }

    @Test
    void oversizedJsonIsRejectedBefore413WorkIsDone() {
        ObjectNode d = defaults();
        d.put("padding", "x".repeat(ThemeValidator.MAX_JSON_BYTES));
        assertThatThrownBy(() -> service.updateTheme(d, null)).isInstanceOf(PayloadTooLargeException.class);
        verify(store, never()).read();
    }

    @Test
    void requiresATenant() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.updateTheme(defaults(), null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void serverManagedFieldsAreStrippedAndColorsLowercased() {
        ObjectNode d = defaults();
        d.put("revision", 999);
        ((ObjectNode) d.get("tokens").get("colors")).put("primary", "#0B3D2E");
        service.updateTheme(d, null);
        verify(store).save(argThat(n -> !n.has("revision") && !n.has("updatedAt")
                && n.at("/tokens/colors/primary").asText().equals("#0b3d2e")), isNull(), any());
    }

    @Test
    void etagHelpers() {
        assertThat(new ThemeSnapshot("{}", 5).etag()).isEqualTo("W/\"5\"");
        assertThat(ThemeSnapshot.parseEtag("W/\"5\"")).isEqualTo(5);
        assertThat(ThemeSnapshot.parseEtag("\"7\"")).isEqualTo(7);
        for (String bad : new String[]{null, "", "5", "W/5", "\"x\"", "\"0\"", "\"-1\"", "\"\"", "W/\"1\" extra"}) {
            assertThatThrownBy(() -> ThemeSnapshot.parseEtag(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
