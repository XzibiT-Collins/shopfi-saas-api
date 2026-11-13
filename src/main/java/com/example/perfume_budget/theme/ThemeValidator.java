package com.example.perfume_budget.theme;

import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a full theme document (docs/api/theme-contract.md): the JSON Schema (classpath copy of
 * docs/api/theme.schema.json, kept identical by a test) plus the rules a schema cannot express: slot/type
 * rules, unique ids and orders, WCAG contrast, rich-text safety, tenant-owned image URLs and existence of
 * referenced products/categories. Produces up to {@value #MAX_ERRORS} field errors.
 */
@Component
public class ThemeValidator {

    public static final int MAX_ERRORS = 50;
    public static final int MAX_JSON_BYTES = 64 * 1024;
    static final int MAX_DEPTH = 12;
    /** Total Markdown code points allowed across all rich-text bodies of one theme document (contract 7.1 rule 4c). */
    public static final int MAX_RICH_TEXT_TOTAL = 8000;
    static final String SCHEMA_RESOURCE = "theme/theme.schema.json";

    private static final Map<String, Set<String>> SLOT_TYPES = Map.of(
            "home", Set.of("hero", "featured-products", "category-grid", "banner", "rich-text"),
            "product", Set.of("featured-products", "banner", "rich-text"),
            "footer", Set.of("footer"));
    private static final Map<String, Integer> SLOT_MAX = Map.of("home", 12, "product", 6, "footer", 1);
    private static final Map<String, String> TYPE_DEF = Map.of(
            "hero", "hero", "featured-products", "featuredProducts", "category-grid", "categoryGrid",
            "banner", "banner", "rich-text", "richText", "footer", "footer");
    private static final String[][] CONTRAST_PAIRS = {
            {"text", "background"}, {"text", "surface"}, {"mutedText", "background"},
            {"mutedText", "surface"}, {"onPrimary", "primary"}, {"onAccent", "accent"}};
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern CLOUD_URL = Pattern.compile("^https://res\\.cloudinary\\.com/([A-Za-z0-9_-]+)/image/upload/(.+)$");
    private static final Pattern VERSION_SEGMENT = Pattern.compile("^v[0-9]{1,12}$");
    /** Final path segment of a branding image: fixed names (optionally with the unique upload suffix). */
    static final Pattern BRANDING_FILE = Pattern.compile(
            "^(?:(?:logo|background)(?:-[0-9a-f]{16})?|section-[a-z0-9-]+)\\.(?:png|jpe?g|webp)$");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$");
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[<>\\u0000-\\u001F\\u007F]");

    private final JsonSchema skeletonSchema;
    private final Map<String, JsonSchema> sectionSchemas = new HashMap<>();
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final String cloudName;

    public ThemeValidator(ObjectMapper mapper,
                          ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          @Value("${storage.cloudinary.cloud-name}") String cloudName) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.cloudName = cloudName;
        JsonNode root;
        try (InputStream in = ThemeValidator.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + SCHEMA_RESOURCE);
            }
            root = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SCHEMA_RESOURCE, e);
        }
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.skeletonSchema = factory.getSchema(root);
        this.skeletonSchema.initializeValidators();
        for (Map.Entry<String, String> e : TYPE_DEF.entrySet()) {
            ObjectNode s = mapper.createObjectNode();
            s.put("$schema", root.get("$schema").asText());
            s.put("$ref", "#/$defs/" + e.getValue());
            s.set("$defs", root.get("$defs"));
            JsonSchema js = factory.getSchema(s);
            js.initializeValidators();
            sectionSchemas.put(e.getKey(), js);
        }
    }

    /** Schema + semantic rules only (no database, no URL ownership). */
    public List<ThemeFieldError> validateStructure(JsonNode doc) {
        Errors errors = new Errors();
        structure(doc, errors);
        errors.finish();
        return errors.list;
    }

    /**
     * Full validation.
     *
     * @param tenantSlug current tenant (for image URL ownership)
     * @param storedUrls image URLs already stored in this tenant's current theme (always acceptable)
     */
    public List<ThemeFieldError> validate(JsonNode doc, String tenantSlug, Set<String> storedUrls) {
        Errors errors = new Errors();
        structure(doc, errors);
        if (doc != null && doc.isObject()) {
            // Also checked when the structure is invalid, so the caller sees every problem in one response.
            checkImageOwnership(doc, tenantSlug, storedUrls, errors);
            checkReferences(doc, errors);
        }
        errors.finish();
        return errors.list;
    }

    // ---------------------------------------------------------------- structure

    private boolean structure(JsonNode doc, Errors errors) {
        if (doc == null || !doc.isObject()) {
            errors.add("", "WRONG_TYPE", "theme must be a JSON object");
            return false;
        }
        if (depth(doc) > MAX_DEPTH) {
            errors.add("", "OUT_OF_RANGE", "theme is nested too deeply");
            return false;
        }
        ObjectNode skeleton = ((ObjectNode) doc).deepCopy();
        skeleton.remove("revision");
        skeleton.remove("updatedAt");
        JsonNode pages = skeleton.get("pages");
        if (pages != null && pages.isObject()) {
            for (String slot : SLOT_TYPES.keySet()) {
                if (pages.get(slot) != null && pages.get(slot).isArray()) {
                    ((ObjectNode) pages).putArray(slot);
                }
            }
        }
        for (ValidationMessage m : skeletonSchema.validate(skeleton)) {
            errors.addMessage(m, skeleton, "");
        }
        JsonNode origPages = doc.get("pages");
        if (origPages != null && origPages.isObject()) {
            Set<String> seenIds = new HashSet<>();
            int[] richTextChars = {0}; // running total of Markdown code points across the whole document
            for (String slot : new TreeSet<>(SLOT_TYPES.keySet())) {
                JsonNode arr = origPages.get(slot);
                if (arr != null && arr.isArray()) {
                    slot(slot, arr, seenIds, richTextChars, errors);
                }
            }
        }
        contrast(doc, errors);
        return errors.isEmpty();
    }

    private void slot(String slot, JsonNode arr, Set<String> seenIds, int[] richTextChars, Errors errors) {
        if (arr.size() > SLOT_MAX.get(slot)) {
            errors.add("pages." + slot, "TOO_MANY_SECTIONS", "at most " + SLOT_MAX.get(slot) + " sections allowed in " + slot);
        }
        Set<Integer> orders = new HashSet<>();
        int limit = Math.min(arr.size(), SLOT_MAX.get(slot) + 1); // never schema-validate an unbounded list
        for (int i = 0; i < limit; i++) {
            JsonNode s = arr.get(i);
            String base = "pages." + slot + "[" + i + "]";
            if (!s.isObject()) {
                errors.add(base, "WRONG_TYPE", "section must be an object");
                continue;
            }
            JsonNode type = s.get("type");
            if (type == null || !type.isTextual()) {
                errors.add(base + ".type", type == null ? "REQUIRED" : "WRONG_TYPE", "type is required and must be a string");
                continue;
            }
            JsonSchema schema = sectionSchemas.get(type.asText());
            if (schema == null) {
                errors.add(base + ".type", "UNKNOWN_SECTION_TYPE", "unknown section type");
                continue;
            }
            if (!SLOT_TYPES.get(slot).contains(type.asText())) {
                errors.add(base + ".type", "SECTION_NOT_ALLOWED_IN_SLOT", type.asText() + " is not allowed in " + slot);
                continue;
            }
            for (ValidationMessage m : schema.validate(s)) {
                errors.addMessage(m, s, base);
            }
            JsonNode id = s.get("id");
            if (id != null && id.isTextual() && !seenIds.add(id.asText())) {
                errors.add(base + ".id", "DUPLICATE_SECTION_ID", "section id is used more than once");
            }
            JsonNode order = s.get("order");
            if (order != null && order.isIntegralNumber() && !orders.add(order.asInt())) {
                errors.add(base + ".order", "DUPLICATE_ORDER", "order is used more than once in " + slot);
            }
            if ("rich-text".equals(type.asText())) {
                JsonNode body = s.path("props").get("body");
                if (body != null && body.isTextual()) {
                    String text = body.asText();
                    String bodyPath = base + ".props.body";
                    // ALWAYS runs, at any length: over-cap bodies come back as TOO_LONG (code points, like the
                    // schema's maxLength), never skipped.
                    RichTextChecker.Problem problem = RichTextChecker.check(text);
                    if (problem != null && !errors.has(bodyPath)) {
                        errors.add(bodyPath, problem.code(), problem.message());
                    }
                    richTextChars[0] += (int) Math.min(Integer.MAX_VALUE / 2, text.codePointCount(0, text.length()));
                    if (richTextChars[0] > MAX_RICH_TEXT_TOTAL && !errors.has(bodyPath)) {
                        errors.add(bodyPath, "RICH_TEXT_BUDGET_EXCEEDED",
                                "all rich-text bodies together may hold at most " + MAX_RICH_TEXT_TOTAL + " characters");
                    }
                }
            }
        }
    }

    private void contrast(JsonNode doc, Errors errors) {
        JsonNode colors = doc.path("tokens").path("colors");
        if (!colors.isObject()) {
            return;
        }
        for (String[] p : CONTRAST_PAIRS) {
            JsonNode fg = colors.get(p[0]);
            JsonNode bg = colors.get(p[1]);
            if (fg == null || bg == null || !fg.isTextual() || !bg.isTextual()
                    || !HEX.matcher(fg.asText()).matches() || !HEX.matcher(bg.asText()).matches()) {
                continue;
            }
            double ratio = contrastRatio(fg.asText(), bg.asText());
            if (ratio < 4.5) {
                errors.add("tokens.colors." + p[0], "CONTRAST_TOO_LOW",
                        String.format(Locale.ROOT, "%s on %s has contrast %.1f:1, need 4.5:1", p[0], p[1], ratio));
            }
        }
    }

    static double contrastRatio(String hexA, String hexB) {
        double a = luminance(hexA);
        double b = luminance(hexB);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static int depth(JsonNode root) {
        int max = 0;
        Deque<JsonNode> nodes = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        nodes.push(root);
        depths.push(1);
        while (!nodes.isEmpty()) {
            JsonNode n = nodes.pop();
            int d = depths.pop();
            max = Math.max(max, d);
            if (max > MAX_DEPTH) {
                return max;
            }
            for (JsonNode child : n) {
                nodes.push(child);
                depths.push(d + 1);
            }
        }
        return max;
    }

    // ---------------------------------------------------------------- ownership and references

    private void checkImageOwnership(JsonNode doc, String tenantSlug, Set<String> storedUrls, Errors errors) {
        check(doc.path("assets").path("logo").get("url"), "assets.logo.url", tenantSlug, storedUrls, errors);
        check(doc.path("assets").path("background").get("url"), "assets.background.url", tenantSlug, storedUrls, errors);
        JsonNode home = doc.path("pages").path("home");
        if (home.isArray()) {
            for (int i = 0; i < Math.min(home.size(), SLOT_MAX.get("home") + 1); i++) {
                JsonNode s = home.get(i);
                if (s.isObject() && "hero".equals(s.path("type").asText())) {
                    check(s.path("props").get("imageUrl"), "pages.home[" + i + "].props.imageUrl", tenantSlug, storedUrls, errors);
                }
            }
        }
    }

    private void check(JsonNode url, String path, String tenantSlug, Set<String> storedUrls, Errors errors) {
        if (url == null || !url.isTextual() || errors.has(path)) {
            return;
        }
        String u = url.asText();
        if (storedUrls != null && storedUrls.contains(u)) {
            return;
        }
        if (!ownedByTenant(u, tenantSlug)) {
            errors.add(path, "ASSET_URL_NOT_OWNED", "image must be uploaded to this store's branding folder");
        }
    }

    /** True if {@code url} is on the configured cloud and points at exactly {@code shopfi/<slug>/branding/<file>}. */
    boolean ownedByTenant(String url, String tenantSlug) {
        if (tenantSlug == null || !SLUG.matcher(tenantSlug).matches()) {
            return false;
        }
        Matcher m = CLOUD_URL.matcher(url);
        if (!m.matches() || !m.group(1).equals(cloudName)) {
            return false;
        }
        String rest = m.group(2);
        if (rest.indexOf('%') >= 0 || rest.contains("..") || rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0) {
            return false;
        }
        // Exactly: [v<digits>/]shopfi/<slug>/branding/<file>. No transformation segments (l_fetch, l_text,
        // fl_attachment, w_/h_/e_ ...): the platform cloud must never be made to fetch or compose third-party content.
        String[] seg = rest.split("/", -1);
        int i = 0;
        if (seg.length > 0 && VERSION_SEGMENT.matcher(seg[0]).matches()) {
            i = 1;
        }
        return seg.length == i + 4 && seg[i].equals("shopfi") && seg[i + 1].equals(tenantSlug)
                && seg[i + 2].equals("branding") && BRANDING_FILE.matcher(seg[i + 3]).matches();
    }

    /** Synthetic tenant-owned URL used while an upload is pending (replaced by the real secure URL). */
    String placeholderUrl(String tenantSlug, String name) {
        return "https://res.cloudinary.com/" + cloudName + "/image/upload/shopfi/" + tenantSlug + "/branding/" + name + ".png";
    }

    /** Public id (relative to the folder) of a tenant-owned URL, e.g. {@code logo}; null if not owned. */
    String brandingName(String url, String tenantSlug) {
        if (url == null || !ownedByTenant(url, tenantSlug)) {
            return null;
        }
        String last = url.substring(url.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(0, dot) : last;
    }

    private void checkReferences(JsonNode doc, Errors errors) {
        Map<Long, List<String>> productRefs = new LinkedHashMap<>();
        Map<Long, List<String>> categoryRefs = new LinkedHashMap<>();
        for (String slot : new String[]{"home", "product"}) {
            JsonNode arr = doc.path("pages").path(slot);
            if (!arr.isArray()) {
                continue;
            }
            for (int i = 0; i < Math.min(arr.size(), SLOT_MAX.get(slot) + 1); i++) {
                JsonNode s = arr.get(i);
                if (!s.isObject()) {
                    continue;
                }
                String type = s.path("type").asText();
                JsonNode props = s.path("props");
                String base = "pages." + slot + "[" + i + "].props";
                if ("featured-products".equals(type)) {
                    addId(categoryRefs, props.get("categoryId"), base + ".categoryId");
                    addIds(productRefs, props.get("productIds"), base + ".productIds");
                } else if ("category-grid".equals(type)) {
                    addIds(categoryRefs, props.get("categoryIds"), base + ".categoryIds");
                }
            }
        }
        report(productRefs, productRepository::findExistingIds, "product", errors);
        report(categoryRefs, categoryRepository::findExistingIds, "category", errors);
    }

    private static void addId(Map<Long, List<String>> into, JsonNode n, String path) {
        if (n != null && n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= 1) {
            into.computeIfAbsent(n.asLong(), k -> new ArrayList<>()).add(path);
        }
    }

    private static void addIds(Map<Long, List<String>> into, JsonNode arr, String path) {
        if (arr != null && arr.isArray()) {
            for (int i = 0; i < Math.min(arr.size(), 13); i++) {
                addId(into, arr.get(i), path + "[" + i + "]");
            }
        }
    }

    private static void report(Map<Long, List<String>> refs, java.util.function.Function<Collection<Long>, List<Long>> lookup,
                               String what, Errors errors) {
        if (refs.isEmpty()) {
            return;
        }
        Set<Long> existing = new HashSet<>(lookup.apply(refs.keySet()));
        refs.forEach((id, paths) -> {
            if (!existing.contains(id)) {
                paths.forEach(p -> errors.add(p, "UNKNOWN_REFERENCE", what + " " + id + " does not exist"));
            }
        });
    }

    // ---------------------------------------------------------------- error accumulation

    private static final class Errors {
        private static final Set<String> COMPOSITE = Set.of("oneOf", "anyOf", "allOf", "if", "then", "else");
        final List<ThemeFieldError> list = new ArrayList<>();
        final List<ThemeFieldError> composites = new ArrayList<>();

        /** Drops branch noise: a WRONG_TYPE on an object that also has more specific child errors. */
        void finish() {
            list.removeIf(e -> e.code().equals("WRONG_TYPE") && !e.path().isEmpty()
                    && list.stream().anyMatch(o -> o != e && (o.path().startsWith(e.path() + ".") || o.path().startsWith(e.path() + "["))));
            if (list.isEmpty()) {
                composites.stream().limit(MAX_ERRORS).forEach(c -> add(c.path(), c.code(), c.message()));
            }
        }
        private final Set<String> keys = new HashSet<>();

        boolean isEmpty() {
            return list.isEmpty();
        }

        boolean has(String path) {
            return list.stream().anyMatch(e -> e.path().equals(path));
        }

        void add(String path, String code, String message) {
            if (list.size() < MAX_ERRORS && keys.add(path + "|" + code)) {
                list.add(new ThemeFieldError(path, code, message));
            }
        }

        void addMessage(ValidationMessage m, JsonNode root, String prefix) {
            String rel = pathOf(m.getInstanceLocation().toString());
            String keyword = m.getType();
            if (("required".equals(keyword) || "additionalProperties".equals(keyword) || "dependentRequired".equals(keyword))
                    && m.getProperty() != null) {
                rel = join(rel, m.getProperty());
            }
            String path = join(prefix, rel);
            if (keyword != null && COMPOSITE.contains(keyword)) {
                // oneOf/anyOf wrappers only repeat their branches' errors; kept as a fallback if nothing else is found.
                composites.add(new ThemeFieldError(path, "INVALID_FORMAT", "is invalid"));
                return;
            }
            JsonNode value = valueAt(root, rel);
            String code;
            String text;
            switch (keyword == null ? "" : keyword) {
                case "required", "dependentRequired" -> { code = "REQUIRED"; text = "is required"; }
                case "additionalProperties" -> { code = "UNKNOWN_PROPERTY"; text = "unknown property"; }
                case "type" -> { code = "WRONG_TYPE"; text = "has the wrong type"; }
                case "pattern" -> {
                    if (path.endsWith("url") || path.endsWith("Url") || path.endsWith("href") || path.endsWith("Href")) {
                        code = "URL_NOT_ALLOWED"; text = "URL is not allowed";
                    } else if (value != null && value.isTextual() && UNSAFE_CHARS.matcher(value.asText()).find()) {
                        code = "UNSAFE_CONTENT"; text = "must not contain angle brackets or control characters";
                    } else {
                        code = "INVALID_FORMAT"; text = "has an invalid format";
                    }
                }
                case "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum" -> { code = "OUT_OF_RANGE"; text = "is out of range"; }
                case "maxLength", "maxItems" -> { code = "TOO_LONG"; text = "is too long"; }
                case "minLength", "minItems" -> { code = "TOO_SHORT"; text = "is too short"; }
                case "enum" -> { code = "NOT_ALLOWED_VALUE"; text = "is not an allowed value"; }
                case "const" -> {
                    if (path.equals("schemaVersion")) {
                        code = "UNSUPPORTED_SCHEMA_VERSION"; text = "unsupported schemaVersion";
                    } else {
                        code = "NOT_ALLOWED_VALUE"; text = "is not an allowed value";
                    }
                }
                case "not" -> {
                    if (path.endsWith("props.body")) {
                        code = "UNSAFE_CONTENT"; text = "contains forbidden Markdown (image or script link)";
                    } else {
                        code = "UNKNOWN_PROPERTY"; text = "is not allowed with this configuration";
                    }
                }
                default -> { code = "INVALID_FORMAT"; text = "is invalid"; }
            }
            add(path, code, text);
        }

        private static String join(String a, String b) {
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            return b.startsWith("[") ? a + b : a + "." + b;
        }

        private static String pathOf(String jsonPath) {
            String p = jsonPath;
            if (p.startsWith("$")) {
                p = p.substring(1);
            }
            return p.startsWith(".") ? p.substring(1) : p;
        }

        private static JsonNode valueAt(JsonNode root, String rel) {
            if (rel.isEmpty()) {
                return root;
            }
            JsonNode cur = root;
            for (String part : rel.split("\\.")) {
                int b = part.indexOf('[');
                String name = b >= 0 ? part.substring(0, b) : part;
                if (!name.isEmpty()) {
                    cur = cur == null ? null : cur.get(name);
                }
                while (b >= 0 && cur != null) {
                    int e = part.indexOf(']', b);
                    cur = cur.get(Integer.parseInt(part.substring(b + 1, e)));
                    b = part.indexOf('[', e);
                }
                if (cur == null) {
                    return null;
                }
            }
            return cur;
        }
    }
}
