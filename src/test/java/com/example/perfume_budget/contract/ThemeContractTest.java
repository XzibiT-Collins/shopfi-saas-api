package com.example.perfume_budget.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THEME-03 contract test. Plain unit test (no Spring context, no database): validates the JSON examples
 * embedded in docs/api/theme-contract.md and a set of hand-built invalid payloads against
 * docs/api/theme.schema.json. Fails if the doc and the schema drift apart.
 */
class ThemeContractTest {

    private static final Path DOCS = Path.of("docs", "api");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema schema;
    private static Map<String, JsonNode> validExamples = new LinkedHashMap<>();
    private static Map<String, JsonNode> invalidExamples = new LinkedHashMap<>();

    @BeforeAll
    static void load() throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        schema = factory.getSchema(Files.readString(DOCS.resolve("theme.schema.json")));
        schema.initializeValidators();

        Pattern p = Pattern.compile("<!-- example: (\\S+) (valid|invalid) -->\\s*```json\\s*(.*?)```", Pattern.DOTALL);
        Matcher m = p.matcher(Files.readString(DOCS.resolve("theme-contract.md")));
        while (m.find()) {
            JsonNode node = MAPPER.readTree(m.group(3));
            ("valid".equals(m.group(2)) ? validExamples : invalidExamples).put(m.group(1), node);
        }
    }

    private static Set<ValidationMessage> validate(JsonNode n) {
        return schema.validate(n);
    }

    private static ObjectNode acme() {
        return (ObjectNode) validExamples.get("acme-full").deepCopy();
    }

    private static ObjectNode section(ObjectNode theme, String slot, String id) {
        for (JsonNode s : theme.get("pages").get(slot)) {
            if (id.equals(s.get("id").asText())) return (ObjectNode) s;
        }
        throw new AssertionError("no section " + id);
    }

    @Test
    void schemaItselfIsValidDraft2020_12() throws IOException {
        JsonSchema meta = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(java.net.URI.create("https://json-schema.org/draft/2020-12/schema"));
        JsonNode schemaNode = MAPPER.readTree(Files.readString(DOCS.resolve("theme.schema.json")));
        assertEquals("https://json-schema.org/draft/2020-12/schema", schemaNode.get("$schema").asText());
        assertTrue(meta.validate(schemaNode).isEmpty());
    }

    @Test
    void frontendCopyIsByteIdentical() throws IOException {
        assertArrayEquals(Files.readAllBytes(DOCS.resolve("theme.schema.json")),
                Files.readAllBytes(DOCS.resolve("frontend").resolve("theme.schema.json")));
    }

    @Test
    void docContainsExpectedExamples() {
        assertTrue(validExamples.keySet().containsAll(Set.of("default-new-tenant", "acme-full", "bravo-minimal", "morrow-canonical")), validExamples.keySet().toString());
        assertFalse(invalidExamples.isEmpty());
    }

    // ---- SF-BE-01: theme catalog ----

    private static JsonNode catalogFile() throws IOException {
        return MAPPER.readTree(Files.readString(DOCS.resolve("frontend").resolve("theme-catalog.json")));
    }

    @Test
    void schemaDeclaresOptionalThemeIdWithIdPattern() {
        JsonNode props = schemaNodeProps();
        assertEquals("string", props.get("themeId").get("type").asText());
        assertFalse(((com.fasterxml.jackson.databind.node.ArrayNode) schemaRoot().get("required")).toString().contains("themeId"));
        for (String ok : new String[]{"morrow", "custom-legacy", "a", "a1-b2"}) {
            ObjectNode t = acme();
            t.put("themeId", ok);
            assertTrue(validate(t).isEmpty(), ok);
        }
        for (String bad : new String[]{"Morrow", "-x", "has space", "", "x".repeat(41), "a_b"}) {
            ObjectNode t = acme();
            t.put("themeId", bad);
            assertFalse(validate(t).isEmpty(), bad);
        }
        ObjectNode notString = acme();
        notString.put("themeId", 5);
        assertFalse(validate(notString).isEmpty());
    }

    private static JsonNode schemaRoot() {
        try {
            return MAPPER.readTree(Files.readString(DOCS.resolve("theme.schema.json")));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonNode schemaNodeProps() {
        return schemaRoot().get("properties");
    }

    @Test
    void everyCatalogThemeYieldsASchemaValidDocument() throws IOException {
        for (JsonNode t : catalogFile().get("themes")) {
            ObjectNode doc = (ObjectNode) validExamples.get("morrow-canonical").deepCopy();
            doc.put("themeId", t.get("id").asText());
            doc.set("tokens", t.get("tokens"));
            assertTrue(validate(doc).isEmpty(), t.get("id") + ": " + validate(doc));
        }
    }

    @Test
    void docSection33ListsEverySocialPlatformAndTheCapsMatchTheSchema() throws IOException {
        String doc = Files.readString(DOCS.resolve("theme-contract.md"));
        String section = doc.substring(doc.indexOf("### 3.3 "), doc.indexOf("## 4. v1 section types"));
        JsonNode social = schemaRoot().at("/$defs/social/properties");
        assertEquals(Set.of("instagram", "facebook", "x", "tiktok", "youtube", "whatsapp", "linkedin", "pinterest"),
                Set.copyOf(java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(social.fieldNames(), 0), false).toList()));
        social.fieldNames().forEachRemaining(k -> {
            assertTrue(section.contains("| `" + k + "` |"), k);
            assertEquals(256, social.get(k).get("maxLength").asInt(), k);
        });
        assertEquals(false, schemaRoot().at("/$defs/social/additionalProperties").asBoolean(true));
        assertTrue(section.contains("**256**"));
        // story caps documented and enforced by the schema
        assertEquals(600, schemaRoot().at("/$defs/story/properties/props/properties/body/maxLength").asInt());
        assertEquals(80, schemaRoot().at("/$defs/story/properties/props/properties/heading/maxLength").asInt());
        assertTrue(doc.contains("1..**600** characters"));
        assertEquals(160, schemaRoot().at("/$defs/banner/properties/props/properties/message/maxLength").asInt());
        assertEquals(3, schemaRoot().at("/$defs/footer/properties/props/properties/linkGroups/maxItems").asInt());
        assertEquals(6, schemaRoot().at("/$defs/footer/properties/props/properties/linkGroups/items/properties/links/maxItems").asInt());
    }

    @Test
    void docSection25ListsEveryCatalogThemeAndColour_andTheCanonicalExampleIsMorrow() throws IOException {
        String doc = Files.readString(DOCS.resolve("theme-contract.md"));
        String section = doc.substring(doc.indexOf("### 2.5 "), doc.indexOf("## 3. Theme document"));
        for (JsonNode t : catalogFile().get("themes")) {
            assertTrue(section.contains("| `" + t.get("id").asText() + "` | " + t.get("name").asText() + " |"), t.get("id").asText());
            t.at("/tokens/colors").forEach(c -> assertTrue(section.contains("`" + c.asText() + "`"), c.asText()));
        }
        assertEquals(catalogFile().get("defaultThemeId").asText(), "morrow");
        JsonNode morrow = catalogFile().get("themes").get(0);
        assertEquals("morrow", morrow.get("id").asText());
        assertEquals(morrow.get("tokens"), validExamples.get("morrow-canonical").get("tokens"));
        assertEquals("morrow", validExamples.get("morrow-canonical").get("themeId").asText());
    }

    @Test
    void everyValidExampleInDocPassesSchema() {
        validExamples.forEach((name, node) -> assertTrue(validate(node).isEmpty(), name + ": " + validate(node)));
    }

    @Test
    void everyInvalidExampleInDocFailsSchema() {
        invalidExamples.forEach((name, node) -> assertFalse(validate(node).isEmpty(), name));
    }

    @Test
    void validExamplesCoverAllV1SectionTypesAndSlots() {
        Set<String> types = new java.util.TreeSet<>();
        validExamples.values().forEach(t -> t.get("pages").forEach(slot -> slot.forEach(s -> types.add(s.get("type").asText()))));
        assertEquals(Set.of("hero", "featured-products", "category-grid", "banner", "rich-text", "footer", "story"), types);
    }

    @Test
    void validExamplesMeetContrastRules() {
        validExamples.forEach((name, t) -> {
            JsonNode c = t.get("tokens").get("colors");
            String[][] pairs = {{"text", "background"}, {"text", "surface"}, {"mutedText", "background"},
                    {"mutedText", "surface"}, {"onPrimary", "primary"}, {"onAccent", "accent"}};
            for (String[] pr : pairs) {
                double r = contrast(c.get(pr[0]).asText(), c.get(pr[1]).asText());
                assertTrue(r >= 4.5, name + " " + pr[0] + " on " + pr[1] + " = " + r);
            }
        });
    }

    @Test
    void contrastHelperMatchesKnownValues() {
        assertEquals(21.0, contrast("#000000", "#ffffff"), 0.01);
        assertTrue(contrast("#777777", "#888888") < 4.5);
    }

    // ---- negative cases ----

    @Test
    void rejectsUnknownSectionType() {
        ObjectNode t = acme();
        section(t, "home", "hero").put("type", "carousel");
        assertFalse(validate(t).isEmpty());
    }

    @Test
    void rejectsBadColors() {
        for (String bad : new String[]{"red", "#12345", "#12345678", "111827", "rgb(0,0,0)", "#gggggg", "var(--x)"}) {
            ObjectNode t = acme();
            ((ObjectNode) t.get("tokens").get("colors")).put("primary", bad);
            assertFalse(validate(t).isEmpty(), bad);
        }
    }

    @Test
    void rejectsFontOutsideAllowList() {
        ObjectNode t = acme();
        ((ObjectNode) t.get("tokens").get("typography")).put("bodyFont", "Comic Sans MS");
        assertFalse(validate(t).isEmpty());
    }

    @Test
    void rejectsOversizedText() {
        ObjectNode t = acme();
        ((ObjectNode) section(t, "home", "hero").get("props")).put("heading", "x".repeat(81));
        assertFalse(validate(t).isEmpty());

        ObjectNode t2 = acme();
        ((ObjectNode) section(t2, "home", "story").get("props")).put("body", "a".repeat(5001));
        assertFalse(validate(t2).isEmpty());
    }

    @Test
    void rejectsScriptAndHtmlInRichText() {
        for (String bad : new String[]{
                "hello <script>alert(1)</script>",
                "<img src=x onerror=alert(1)>",
                "[x](javascript:alert(1))",
                "[x]( JaVaScRiPt:alert(1))",
                "[x](data:text/html;base64,AAAA)",
                "![pic](https://example.com/a.png)"}) {
            ObjectNode t = acme();
            ((ObjectNode) section(t, "home", "story").get("props")).put("body", bad);
            assertFalse(validate(t).isEmpty(), bad);
        }
    }

    @Test
    void rejectsHtmlInPlainTextFields() {
        ObjectNode t = acme();
        ((ObjectNode) section(t, "home", "hero").get("props")).put("subheading", "<b>bold</b>");
        assertFalse(validate(t).isEmpty());
    }

    @Test
    void rejectsBadSchemaVersion() {
        for (Object bad : new Object[]{2, 0, "1", 1.5}) {
            ObjectNode t = acme();
            if (bad instanceof Integer i) t.put("schemaVersion", i);
            else if (bad instanceof Double d) t.put("schemaVersion", d);
            else t.put("schemaVersion", (String) bad);
            assertFalse(validate(t).isEmpty(), String.valueOf(bad));
        }
        ObjectNode missing = acme();
        missing.remove("schemaVersion");
        assertFalse(validate(missing).isEmpty());
    }

    @Test
    void rejectsUnsafeUrls() {
        ObjectNode t = acme();
        ((ObjectNode) section(t, "home", "hero").get("props")).put("ctaHref", "javascript:alert(1)");
        assertFalse(validate(t).isEmpty());

        ObjectNode t2 = acme();
        ((ObjectNode) section(t2, "home", "hero").get("props")).put("ctaHref", "//evil.example.com");
        assertFalse(validate(t2).isEmpty());

        ObjectNode t3 = acme();
        ((ObjectNode) section(t3, "home", "hero").get("props")).put("imageUrl", "https://evil.example.com/a.png");
        assertFalse(validate(t3).isEmpty());

        ObjectNode t4 = acme();
        ((ObjectNode) t4.get("assets").get("logo")).put("url", "http://res.cloudinary.com/x/image/upload/a.png");
        assertFalse(validate(t4).isEmpty());

        ObjectNode t5 = acme();
        ((ObjectNode) section(t5, "footer", "footer").get("props").get("social").get(0)).put("url", "https://evil.example.com/acme");
        assertFalse(validate(t5).isEmpty());
    }

    @Test
    void rejectsSectionInWrongSlotAndTooManySections() {
        ObjectNode t = acme();
        ArrayNode footer = (ArrayNode) t.get("pages").get("footer");
        footer.add(section(t, "home", "hero").deepCopy());
        assertFalse(validate(t).isEmpty());

        ObjectNode t2 = acme();
        ArrayNode home = (ArrayNode) t2.get("pages").get("home");
        for (int i = 0; i < 6; i++) {
            ObjectNode extra = section(t2, "home", "promo").deepCopy();
            extra.put("id", "extra-" + i);
            home.add(extra);
        }
        assertFalse(validate(t2).isEmpty()); // 13 > 12
    }

    @Test
    void rejectsInconsistentFeaturedProductsAndUnknownProps() {
        ObjectNode t = acme();
        ((ObjectNode) section(t, "home", "pick").get("props")).remove("productIds");
        assertFalse(validate(t).isEmpty());

        ObjectNode t2 = acme();
        ((ObjectNode) section(t2, "home", "best-sellers").get("props")).put("categoryId", 3);
        assertFalse(validate(t2).isEmpty());

        ObjectNode t3 = acme();
        ((ObjectNode) section(t3, "home", "hero").get("props")).put("customCss", "body{display:none}");
        assertFalse(validate(t3).isEmpty());

        ObjectNode t4 = acme();
        ((ObjectNode) section(t4, "home", "hero").get("props")).remove("ctaHref");
        assertFalse(validate(t4).isEmpty());
    }

    // ---- helpers ----

    private static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double[] c = {(rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
