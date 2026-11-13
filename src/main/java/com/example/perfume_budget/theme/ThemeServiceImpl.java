package com.example.perfume_budget.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Theme read/update. Reads are cached in the {@code theme} cache, which {@code TenantAwareCacheManager}
 * prefixes with the tenant, so A's entry is never B's. Updates evict after success. Uploads happen before the
 * database transaction (which lives in {@link ThemeStore}), never inside it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeServiceImpl implements ThemeService {

    public static final String CACHE = "theme";
    static final String CACHE_KEY = "'active'";
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final ThemeStore store;
    private final ThemeValidator validator;
    private final BrandingUploadService uploads;
    private final CacheManager cacheManager;
    private final ObjectMapper mapper;

    @Override
    @Cacheable(cacheNames = CACHE, key = CACHE_KEY)
    public ThemeSnapshot getActiveTheme() {
        return store.read();
    }

    @Override
    @CacheEvict(cacheNames = CACHE, key = CACHE_KEY)
    public ThemeSnapshot updateTheme(JsonNode theme, Long expectedVersion, ThemeUploads files, String updatedBy) {
        try {
            return doUpdate(theme, expectedVersion, files == null ? ThemeUploads.NONE : files, updatedBy);
        } catch (ThemeConflictException e) {
            evict(); // the cached copy is evidently stale
            throw e;
        }
    }

    private ThemeSnapshot doUpdate(JsonNode theme, Long expectedVersion, ThemeUploads files, String updatedBy) {
        if (theme == null || !theme.isObject()) {
            throw new ThemeValidationException(new ThemeFieldError("", "WRONG_TYPE", "theme must be a JSON object"));
        }
        if (theme.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ThemeValidator.MAX_JSON_BYTES) {
            throw new PayloadTooLargeException("Theme document exceeds " + ThemeValidator.MAX_JSON_BYTES / 1024 + " KB");
        }
        String slug = BrandingUploadService.currentSlug();
        String folder = BrandingUploadService.brandingFolder();

        ThemeSnapshot current = store.read();
        Set<String> storedUrls = imageUrls(current.theme());
        ObjectNode working = normalize(theme);

        // 1. cheap, network-free checks of every file, and placeholders so schema validation can run
        List<ThemeFieldError> pre = new ArrayList<>();
        List<Upload> plan = plan(working, files, slug, pre);
        if (!pre.isEmpty()) {
            throw new ThemeValidationException(pre);
        }

        // 2. full validation before any network call
        List<ThemeFieldError> errors = validator.validate(working, slug, storedUrls);
        if (!errors.isEmpty()) {
            throw new ThemeValidationException(errors);
        }

        // 3. upload, then swap placeholders for the real secure URLs
        Set<String> storedNames = new HashSet<>();
        for (String u : storedUrls) {
            String n = validator.brandingName(u, slug);
            if (n != null) {
                storedNames.add(n);
            }
        }
        List<String> uploadedNames = new ArrayList<>();
        try {
            for (Upload u : plan) {
                String url = uploads.upload(u.kind, u.sectionId, u.file);
                uploadedNames.add(BrandingUploadService.publicId(u.kind, u.sectionId));
                u.apply(working, url);
            }
            if (!plan.isEmpty()) {
                List<ThemeFieldError> after = validator.validate(working, slug, storedUrls);
                if (!after.isEmpty()) {
                    throw new BrandingStorageException("Image store returned an unexpected URL", null);
                }
            }
            ThemeSnapshot saved = store.save(working, expectedVersion, updatedBy);
            cleanupRemoved(storedUrls, working, slug);
            return saved;
        } catch (RuntimeException e) {
            // Only delete assets that did not exist before: fixed public ids are overwritten in place, so an
            // existing asset cannot be rolled back and must not be deleted from under the stored theme.
            for (String name : uploadedNames) {
                if (!storedNames.contains(name)) {
                    try {
                        uploads.delete(name);
                    } catch (RuntimeException ignored) {
                        log.warn("Cleanup of branding asset failed");
                    }
                }
            }
            throw e;
        }
    }

    private void evict() {
        Cache c = cacheManager.getCache(CACHE);
        if (c != null) {
            c.evict("active");
        }
    }

    // ------------------------------------------------------------------ uploads plan

    private static final class Upload {
        final BrandingUploadService.Kind kind;
        final String sectionId;
        final BrandingFile file;
        final java.util.function.BiConsumer<ObjectNode, String> setter;

        Upload(BrandingUploadService.Kind kind, String sectionId, BrandingFile file,
               java.util.function.BiConsumer<ObjectNode, String> setter) {
            this.kind = kind;
            this.sectionId = sectionId;
            this.file = file;
            this.setter = setter;
        }

        void apply(ObjectNode doc, String url) {
            setter.accept(doc, url);
        }
    }

    private List<Upload> plan(ObjectNode working, ThemeUploads files, String slug, List<ThemeFieldError> errors) {
        List<Upload> plan = new ArrayList<>();
        if (files.logo() != null) {
            checkFile(BrandingUploadService.Kind.LOGO, files.logo(), "logo", errors);
            JsonNode logo = working.path("assets").path("logo");
            if (!logo.isObject() || !logo.hasNonNull("alt")) {
                errors.add(new ThemeFieldError("assets.logo.alt", "REQUIRED", "alt text is required when uploading a logo"));
            } else {
                BrandingUploadService.Kind k = BrandingUploadService.Kind.LOGO;
                String name = BrandingUploadService.publicId(k, null);
                ((ObjectNode) logo).put("url", validator.placeholderUrl(slug, name));
                plan.add(new Upload(k, null, files.logo(), (d, u) -> ((ObjectNode) d.get("assets").get("logo")).put("url", u)));
            }
        }
        if (files.background() != null) {
            checkFile(BrandingUploadService.Kind.BACKGROUND, files.background(), "background", errors);
            BrandingUploadService.Kind k = BrandingUploadService.Kind.BACKGROUND;
            JsonNode assets = working.get("assets");
            if (assets != null && assets.isObject()) {
                JsonNode bg = assets.get("background");
                ObjectNode target;
                if (bg != null && bg.isObject()) {
                    target = (ObjectNode) bg;
                } else if (bg == null || bg.isNull()) {
                    target = ((ObjectNode) assets).putObject("background");
                } else {
                    target = null;
                }
                if (target != null) {
                    target.put("url", validator.placeholderUrl(slug, BrandingUploadService.publicId(k, null)));
                    plan.add(new Upload(k, null, files.background(), (d, u) -> ((ObjectNode) d.get("assets").get("background")).put("url", u)));
                }
            }
        }
        for (Map.Entry<String, BrandingFile> e : new TreeMap<>(files.sectionImages()).entrySet()) {
            String id = e.getKey();
            String path = "image." + id;
            if (id == null || !id.matches("^[a-z0-9][a-z0-9-]{0,39}$")) {
                errors.add(new ThemeFieldError(path, "INVALID_FORMAT", "invalid section id"));
                continue;
            }
            checkFile(BrandingUploadService.Kind.SECTION_IMAGE, e.getValue(), path, errors);
            ObjectNode hero = findHero(working, id);
            if (hero == null) {
                errors.add(new ThemeFieldError(path, "UNKNOWN_REFERENCE", "no hero section with this id in the theme"));
                continue;
            }
            BrandingUploadService.Kind k = BrandingUploadService.Kind.SECTION_IMAGE;
            ((ObjectNode) hero.get("props")).put("imageUrl", validator.placeholderUrl(slug, BrandingUploadService.publicId(k, id)));
            plan.add(new Upload(k, id, e.getValue(), (d, u) -> {
                ObjectNode h = findHero(d, id);
                if (h != null) {
                    ((ObjectNode) h.get("props")).put("imageUrl", u);
                }
            }));
        }
        return plan;
    }

    private void checkFile(BrandingUploadService.Kind kind, BrandingFile file, String path, List<ThemeFieldError> errors) {
        try {
            uploads.validate(kind, file, path);
        } catch (ThemeValidationException ex) {
            errors.addAll(ex.getErrors());
        }
    }

    private static ObjectNode findHero(JsonNode doc, String id) {
        JsonNode home = doc.path("pages").path("home");
        if (home.isArray()) {
            for (JsonNode s : home) {
                if (s.isObject() && "hero".equals(s.path("type").asText()) && id.equals(s.path("id").asText())
                        && s.path("props").isObject()) {
                    return (ObjectNode) s;
                }
            }
        }
        return null;
    }

    private void cleanupRemoved(Set<String> storedUrls, JsonNode newDoc, String slug) {
        Set<String> keep = imageUrls(newDoc);
        for (String url : storedUrls) {
            String name = validator.brandingName(url, slug);
            if (name != null && keep.stream().noneMatch(k -> name.equals(validator.brandingName(k, slug)))) {
                try {
                    uploads.delete(name);
                } catch (RuntimeException e) {
                    log.warn("Best-effort removal of old branding asset failed");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Deep copy without server-managed members and with colours lower-cased. */
    ObjectNode normalize(JsonNode theme) {
        ObjectNode copy = ((ObjectNode) theme).deepCopy();
        copy.remove("revision");
        copy.remove("updatedAt");
        JsonNode colors = copy.path("tokens").path("colors");
        if (colors.isObject()) {
            ObjectNode c = (ObjectNode) colors;
            List<String> names = new ArrayList<>();
            c.fieldNames().forEachRemaining(names::add);
            for (String n : names) {
                JsonNode v = c.get(n);
                if (v.isTextual() && HEX.matcher(v.asText()).matches()) {
                    c.put(n, v.asText().toLowerCase(Locale.ROOT));
                }
            }
        }
        return copy;
    }

    static Set<String> imageUrls(JsonNode doc) {
        Set<String> urls = new HashSet<>();
        add(urls, doc.path("assets").path("logo").get("url"));
        add(urls, doc.path("assets").path("background").get("url"));
        JsonNode home = doc.path("pages").path("home");
        if (home.isArray()) {
            for (JsonNode s : home) {
                if (s.isObject() && "hero".equals(s.path("type").asText())) {
                    add(urls, s.path("props").get("imageUrl"));
                }
            }
        }
        return urls;
    }

    private static void add(Set<String> into, JsonNode n) {
        if (n != null && n.isTextual()) {
            into.add(n.asText());
        }
    }
}
