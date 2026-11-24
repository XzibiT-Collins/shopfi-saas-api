package com.example.perfume_budget.theme;

import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.example.perfume_budget.theme.ThemeTestDocs.MAPPER;
import static com.example.perfume_budget.theme.ThemeTestDocs.defaults;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** SF-BE-01 AC1: the catalog file is valid, and every theme passes the REAL validator (WCAG pair rules, fonts, schema). */
class ThemeCatalogTest {

    static final Path FILE = Path.of("docs", "api", "frontend", "theme-catalog.json");
    static final String[][] PAIRS = {{"text", "background"}, {"text", "surface"}, {"mutedText", "background"},
            {"mutedText", "surface"}, {"onPrimary", "primary"}, {"onAccent", "accent"}};

    final ThemeValidator validator = new ThemeValidator(MAPPER, mock(ProductRepository.class), mock(CategoryRepository.class), "test");
    final ThemeCatalog catalog = new ThemeCatalog(MAPPER);

    static JsonNode file() throws IOException {
        return MAPPER.readTree(Files.readString(FILE));
    }

    static List<JsonNode> themes() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        file().get("themes").forEach(out::add);
        return out;
    }

    private ObjectNode docWith(JsonNode tokens) {
        ObjectNode d = defaults();
        d.set("tokens", tokens.deepCopy());
        return d;
    }

    @Test
    void catalogEnvelopeAndIds() throws IOException {
        JsonNode f = file();
        assertThat(f.get("catalogVersion").asInt()).isEqualTo(1);
        assertThat(f.get("defaultThemeId").asText()).isEqualTo("morrow").isEqualTo(ThemeCatalog.DEFAULT_ID);
        assertThat(f.get("legacyThemeId").asText()).isEqualTo("custom-legacy").isEqualTo(ThemeCatalog.LEGACY_ID);
        List<String> ids = new ArrayList<>();
        for (JsonNode t : themes()) {
            ids.add(t.get("id").asText());
            assertThat(t.get("name").asText()).isNotBlank();
            assertThat(t.get("description").asText()).isNotBlank();
            assertThat(t.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "description", "tokens");
        }
        assertThat(ids).containsExactly("morrow", "classic", "ocean", "blush", "sand", "slate", "plum", "midnight");
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        assertThat(ids).allMatch(i -> i.matches("^[a-z0-9-]+$"));
        assertThat(ids).doesNotContain("custom-legacy");
        assertThat(catalog.ids()).containsExactlyElementsOf(ids);
    }

    @Test
    void everyThemePassesTheRealValidatorIncludingSixContrastPairs() throws IOException {
        for (JsonNode t : themes()) {
            assertThat(validator.validateStructure(docWith(t.get("tokens"))))
                    .as("theme %s", t.get("id").asText()).isEmpty();
        }
    }

    @Test
    void contrastPairsAreComputedIndependentlyToo_andAccentIsReadableOnBothBackgrounds() throws IOException {
        for (JsonNode t : themes()) {
            JsonNode c = t.at("/tokens/colors");
            for (String[] p : PAIRS) {
                assertThat(ThemeValidator.contrastRatio(c.get(p[0]).asText(), c.get(p[1]).asText()))
                        .as("%s %s on %s", t.get("id").asText(), p[0], p[1]).isGreaterThanOrEqualTo(4.5);
            }
            // storefront draws accent on the page and card surfaces (links, badges): hold the same bar
            for (String bg : new String[]{"background", "surface"}) {
                assertThat(ThemeValidator.contrastRatio(c.get("accent").asText(), c.get(bg).asText()))
                        .as("%s accent on %s", t.get("id").asText(), bg).isGreaterThanOrEqualTo(4.5);
            }
        }
    }

    @Test
    void theValidatorWouldRejectABadTheme_soTheLoopAboveCanFail() throws IOException {
        ObjectNode tokens = (ObjectNode) themes().get(0).get("tokens").deepCopy();
        ((ObjectNode) tokens.get("colors")).put("mutedText", "#cccccc");
        assertThat(validator.validateStructure(docWith(tokens))).extracting(ThemeFieldError::code).contains("CONTRAST_TOO_LOW");
        ObjectNode font = (ObjectNode) themes().get(0).get("tokens").deepCopy();
        ((ObjectNode) font.get("typography")).put("headingFont", "Comic Sans");
        assertThat(validator.validateStructure(docWith(font))).extracting(ThemeFieldError::code).contains("NOT_ALLOWED_VALUE");
    }

    @Test
    void themesKeepTheDesignedShape_onlyColoursAndHeadingFontVary() throws IOException {
        Set<String> colourSets = new HashSet<>();
        for (JsonNode t : themes()) {
            String id = t.get("id").asText();
            JsonNode tk = t.get("tokens");
            assertThat(tk.get("radius").asText()).as(id).isEqualTo("md");
            assertThat(tk.get("spacing").asText()).as(id).isEqualTo("comfortable");
            assertThat(tk.at("/typography/baseSizePx").asInt()).as(id).isEqualTo(16);
            assertThat(tk.at("/typography/bodyFont").asText()).as(id).isEqualTo("Inter");
            String heading = tk.at("/typography/headingFont").asText();
            assertThat(heading).as(id).isEqualTo(switch (id) {
                case "blush" -> "Playfair Display";
                case "sand" -> "Lora";
                default -> "Inter";
            });
            assertThat(colourSets.add(tk.get("colors").toString())).as("distinct colours " + id).isTrue();
        }
    }

    @Test
    void classpathCopyIsByteIdenticalToTheDocsFile() throws IOException {
        byte[] docs = Files.readAllBytes(FILE);
        assertThat(Files.readAllBytes(Path.of("src", "main", "resources", "theme", "theme-catalog.json"))).isEqualTo(docs);
        try (var in = ThemeCatalog.class.getClassLoader().getResourceAsStream(ThemeCatalog.RESOURCE)) {
            assertThat(in.readAllBytes()).isEqualTo(docs);
        }
    }

    @Test
    void selectabilityTokensAreCopiesAndMatching() throws IOException {
        assertThat(catalog.isSelectable("ocean")).isTrue();
        assertThat(catalog.isSelectable("custom-legacy")).isFalse();
        assertThat(catalog.isSelectable("Ocean")).isFalse();
        assertThat(catalog.isSelectable("")).isFalse();
        assertThat(catalog.isSelectable(null)).isFalse();
        assertThatThrownBy(() -> catalog.tokens("nope")).isInstanceOf(IllegalArgumentException.class);

        ObjectNode t = catalog.tokens("ocean");
        ((ObjectNode) t.get("colors")).put("primary", "#000000");
        assertThat(catalog.tokens("ocean").at("/colors/primary").asText()).isEqualTo("#0f3d5c");

        assertThat(catalog.matching(catalog.tokens("slate"))).contains("slate");
        ObjectNode shifted = catalog.tokens("slate");
        ((ObjectNode) shifted.get("colors")).put("accent", "#2f5fd1");
        assertThat(catalog.matching(shifted)).isEmpty();
        ObjectNode upper = catalog.tokens("slate");
        ((ObjectNode) upper.get("colors")).put("primary", "#1E293B");
        assertThat(catalog.matching(upper)).contains("slate");
        assertThat(catalog.matching(null)).isEmpty();
    }
}
