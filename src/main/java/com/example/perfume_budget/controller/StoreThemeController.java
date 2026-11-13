package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.tenancy.TenantResolutionFilter;
import com.example.perfume_budget.theme.BrandingFile;
import com.example.perfume_budget.theme.BrandingUploadService;
import com.example.perfume_budget.theme.PayloadTooLargeException;
import com.example.perfume_budget.theme.ThemeFieldError;
import com.example.perfume_budget.theme.ThemeRequestSizeFilter;
import com.example.perfume_budget.theme.ThemeService;
import com.example.perfume_budget.theme.ThemeSnapshot;
import com.example.perfume_budget.theme.ThemeUploads;
import com.example.perfume_budget.theme.ThemeValidationException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.WebUtils;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Storefront theme API (THEME-04). Contract: {@code docs/api/theme-contract.md} section 5.
 * GET is public (tenant from Origin/Referer via the filters); PUT is ADMIN of the resolved tenant.
 * Exceptions are mapped by {@link StoreThemeExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/theme")
@RequiredArgsConstructor
public class StoreThemeController {

    static final String CACHE_CONTROL = "public, max-age=60, s-maxage=300, stale-while-revalidate=600";
    /**
     * Used when the tenant was NOT resolved from the Origin header (Referer-only or no Origin): a shared cache would
     * key that response as "Origin: absent" and could serve tenant A's theme to tenant B's Origin-less request.
     */
    static final String CACHE_CONTROL_UNKEYED = "private, no-store";
    static final String VARY = "Origin, Referer";
    private static final Pattern SECTION_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    private static final String IMAGE_PREFIX = "image.";
    private static final int MAX_FILE_PARTS = 3 + 12; // theme + logo + background + up to 12 hero images

    private static final ObjectMapper STRICT = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final ThemeService themeService;

    @GetMapping
    public ResponseEntity<CustomApiResponse<JsonNode>> getTheme(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return getTheme(TenantResolutionFilter.tenantSignalIsOrigin(request), ifNoneMatch);
    }

    ResponseEntity<CustomApiResponse<JsonNode>> getTheme(boolean tenantFromOrigin, String ifNoneMatch) {
        ThemeSnapshot snapshot = themeService.getActiveTheme();
        String etag = snapshot.etag();
        ResponseEntity.BodyBuilder ok = ResponseEntity.status(matches(ifNoneMatch, snapshot.version())
                        ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, tenantFromOrigin ? CACHE_CONTROL : CACHE_CONTROL_UNKEYED)
                .header(HttpHeaders.VARY, VARY)
                .eTag(etag);
        if (matches(ifNoneMatch, snapshot.version())) {
            return ok.build();
        }
        return ok.body(CustomApiResponse.success(snapshot.theme()));
    }

    /**
     * One mapping for both forms so that every failure (including a wrong Content-Type) is raised inside the handler
     * and mapped by {@link StoreThemeExceptionHandler}. Spring's {@code consumes} would reject before the handler
     * is chosen, where the scoped advice does not apply.
     * <ul>
     *   <li>{@code application/json}: body is the Theme document, read with a hard 64 KB bound.</li>
     *   <li>{@code multipart/form-data}: parts {@code theme}, {@code logo}, {@code background},
     *       {@code image.<sectionId>}.</li>
     *   <li>anything else: 415.</li>
     * </ul>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<CustomApiResponse<JsonNode>> put(
            HttpServletRequest request, Authentication auth,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch)
            throws IOException, HttpMediaTypeNotSupportedException {
        MediaType type = contentType(request);
        boolean json = MediaType.APPLICATION_JSON.isCompatibleWith(type) && !type.isWildcardType() && !type.isWildcardSubtype();
        boolean multipart = MediaType.MULTIPART_FORM_DATA.isCompatibleWith(type) && !type.isWildcardType() && !type.isWildcardSubtype();
        if (!json && !multipart) {
            throw new HttpMediaTypeNotSupportedException(type, List.of(MediaType.APPLICATION_JSON, MediaType.MULTIPART_FORM_DATA));
        }
        Long expected = parseIfMatch(ifMatch);
        if (json) {
            byte[] body = request.getInputStream().readNBytes((int) ThemeRequestSizeFilter.MAX_JSON_BYTES + 1);
            return stored(themeService.updateTheme(parseTheme(body), expected, ThemeUploads.NONE, auth.getName()));
        }
        // Security wraps the request; unwrap to the multipart request DispatcherServlet created.
        MultipartHttpServletRequest mp = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (mp == null) {
            throw bad("$", "INVALID_FORMAT", "malformed multipart request");
        }
        return putMultipart(mp, auth, expected);
    }

    private static MediaType contentType(HttpServletRequest request) throws HttpMediaTypeNotSupportedException {
        String ct = request.getContentType();
        if (ct == null || ct.isBlank()) {
            throw new HttpMediaTypeNotSupportedException("Content-Type is required");
        }
        try {
            return MediaType.parseMediaType(ct);
        } catch (InvalidMediaTypeException e) {
            throw new HttpMediaTypeNotSupportedException("Invalid Content-Type");
        }
    }

    private ResponseEntity<CustomApiResponse<JsonNode>> putMultipart(MultipartHttpServletRequest request, Authentication auth,
                                                                     Long expected) throws IOException {
        Map<String, List<MultipartFile>> parts = request.getMultiFileMap();
        int fileParts = parts.values().stream().mapToInt(List::size).sum();
        if (fileParts > MAX_FILE_PARTS) {
            throw bad("parts", "INVALID_FORMAT", "too many parts");
        }

        MultipartFile themePart = null;
        BrandingFile logo = null;
        BrandingFile background = null;
        Map<String, BrandingFile> images = new HashMap<>();
        for (Map.Entry<String, List<MultipartFile>> e : parts.entrySet()) {
            String name = e.getKey();
            if (e.getValue().size() != 1) {
                throw bad("parts", "INVALID_FORMAT", "each part may appear only once");
            }
            MultipartFile f = e.getValue().get(0);
            switch (name) {
                case "theme" -> themePart = f;
                case "logo" -> logo = image(f, BrandingUploadService.LOGO_MAX_BYTES);
                case "background" -> background = image(f, BrandingUploadService.BACKGROUND_MAX_BYTES);
                default -> {
                    // Unknown parts are rejected (never silently ignored), and the client-chosen name is
                    // not echoed back or used for anything other than the contract-regex check.
                    if (!name.startsWith(IMAGE_PREFIX) || !SECTION_ID.matcher(name.substring(IMAGE_PREFIX.length())).matches()) {
                        throw bad("parts", "UNKNOWN_PROPERTY", "unknown part; allowed parts: theme, logo, background, image.<sectionId>");
                    }
                    images.put(name.substring(IMAGE_PREFIX.length()),
                            image(f, BrandingUploadService.SECTION_IMAGE_MAX_BYTES));
                }
            }
        }

        byte[] themeBytes;
        if (themePart != null) {
            if (themePart.getSize() > ThemeRequestSizeFilter.MAX_JSON_BYTES) {
                throw new PayloadTooLargeException("Theme document exceeds the 64 KB limit");
            }
            themeBytes = themePart.getBytes();
        } else {
            // A `theme` form field sent without a filename is a text parameter, not a file part.
            String text = request.getParameter("theme");
            if (text == null) {
                throw bad("theme", "REQUIRED", "the theme part is required");
            }
            themeBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (themeBytes.length > ThemeRequestSizeFilter.MAX_JSON_BYTES) {
                throw new PayloadTooLargeException("Theme document exceeds the 64 KB limit");
            }
        }
        JsonNode theme = parseTheme(themeBytes);
        return stored(themeService.updateTheme(theme, expected, new ThemeUploads(logo, background, images), auth.getName()));
    }

    // ------------------------------------------------------------------------------------------------

    private static ResponseEntity<CustomApiResponse<JsonNode>> stored(ThemeSnapshot snapshot) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(snapshot.etag())
                .body(CustomApiResponse.success("Theme updated", snapshot.theme()));
    }

    private static BrandingFile image(MultipartFile f, long max) throws IOException {
        if (f.getSize() > max) { // size is known from the spooled part; nothing is read into memory yet
            throw new PayloadTooLargeException("File exceeds the " + (max / (1024 * 1024)) + " MB limit");
        }
        return new BrandingFile(f.getBytes(), f.getContentType(), f.getOriginalFilename());
    }

    private static JsonNode parseTheme(byte[] body) {
        if (body.length > ThemeRequestSizeFilter.MAX_JSON_BYTES) {
            throw new PayloadTooLargeException("Theme document exceeds the 64 KB limit");
        }
        if (body.length == 0) {
            throw bad("$", "REQUIRED", "request body is empty");
        }
        try {
            JsonNode node = STRICT.readTree(body);
            if (node == null || !node.isObject()) {
                throw bad("$", "WRONG_TYPE", "theme must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw bad("$", "INVALID_FORMAT", "body is not valid JSON");
        } catch (IOException e) {
            throw bad("$", "INVALID_FORMAT", "body could not be read");
        }
    }

    private static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return null;
        }
        try {
            return ThemeSnapshot.parseEtag(ifMatch);
        } catch (IllegalArgumentException e) {
            throw bad("If-Match", "INVALID_FORMAT", "If-Match must be a single ETag from a previous GET");
        }
    }

    private static ThemeValidationException bad(String path, String code, String message) {
        return new ThemeValidationException(new ThemeFieldError(path, code, message));
    }

    /** True when If-None-Match lists the current ETag (weak comparison) or {@code *}. */
    static boolean matches(String ifNoneMatch, long version) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String raw : ifNoneMatch.split(",")) {
            String t = raw.trim();
            if (t.equals("*")) {
                return true;
            }
            try {
                if (ThemeSnapshot.parseEtag(t) == version) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // not one of ours: no match
            }
        }
        return false;
    }
}
