package com.example.perfume_budget.theme;

import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * THEME-06 AC0 (a trailing line terminator must fail EVERY schema pattern, the schema's Java-regex {@code $} lets it through)
 * and AC1 (the rich-text document budget is counted before the checker runs; the checker is skipped once it is exceeded).
 * No Spring, no database.
 */
class ThemeValidatorHardeningTest {

    private static final String LOGO = "https://res.cloudinary.com/shopfi-demo/image/upload/v1/shopfi/acme/branding/logo.png";

    ProductRepository products = mock(ProductRepository.class);
    CategoryRepository categories = mock(CategoryRepository.class);
    ThemeValidator validator;

    @BeforeEach
    void setUp() {
        validator = spy(new ThemeValidator(MAPPER, products, categories, "shopfi-demo"));
        when(products.findExistingIds(anyCollection())).thenAnswer(i -> ((java.util.Collection<Long>) i.getArgument(0)).stream().filter(id -> id <= 100).toList());
        when(categories.findExistingIds(anyCollection())).thenAnswer(i -> ((java.util.Collection<Long>) i.getArgument(0)).stream().filter(id -> id <= 100).toList());
    }

    private List<ThemeFieldError> run(ObjectNode doc) {
        return validator.validate(doc, "acme", Set.of(LOGO));
    }

    private ObjectNode props(ObjectNode doc, String id) {
        return (ObjectNode) section(doc, id).get("props");
    }

    // ---------------------------------------------------------------- AC0

    /** One mutation per schema pattern: hexColor, plainText, link (path and https), social URL, cloudinaryImageUrl. */
    private record Case(String path, java.util.function.BiConsumer<ObjectNode, String> set, String valid) {}

    private static final List<Case> CASES = List.of(
            new Case("tokens.colors.primary", (d, v) -> ((ObjectNode) d.get("tokens").get("colors")).put("primary", v), "#111827"),
            new Case("pages.home[0].props.message", (d, v) -> ((ObjectNode) d.get("pages").get("home").get(0).get("props")).put("message", v), "Hello"),
            new Case("assets.logo.alt", (d, v) -> d.with("assets").with("logo").put("alt", v), "Acme logo"),
            new Case("assets.logo.url", (d, v) -> d.with("assets").with("logo").put("alt", "x").put("url", v), LOGO));

    private ObjectNode doc() {
        return acme();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n", "\u0085", " ", " "})
    void aTrailingLineTerminatorFailsEveryStringPattern(String terminator) {
        for (Case c : CASES) {
            ObjectNode ok = doc();
            c.set().accept(ok, c.valid());
            assertThat(run(ok)).as("control " + c.path()).noneMatch(e -> e.path().equals(c.path()));
            ObjectNode bad = doc();
            c.set().accept(bad, c.valid() + terminator);
            assertThat(run(bad)).as(c.path() + " + " + terminator.chars().boxed().toList())
                    .anyMatch(e -> e.path().equals(c.path()));
            assertThat(validator.validateStructure(bad)).as("structure only: " + c.path())
                    .anyMatch(e -> e.path().equals(c.path()));
        }
    }

    @Test
    void linkAndSocialUrlPatternsRejectATrailingNewlineToo() {
        ObjectNode link = doc();
        ((ObjectNode) props(link, "footer").get("linkGroups").get(0).get("links").get(0)).put("href", "/x\n");
        assertThat(run(link)).anyMatch(e -> e.path().endsWith("links[0].href") && e.code().equals("URL_NOT_ALLOWED"));
        ObjectNode https = doc();
        ((ObjectNode) props(https, "footer").get("linkGroups").get(0).get("links").get(0)).put("href", "https://example.com/a\r\n");
        assertThat(run(https)).anyMatch(e -> e.path().endsWith("links[0].href") && e.code().equals("URL_NOT_ALLOWED"));
        ObjectNode social = doc();
        ((ObjectNode) props(social, "footer").get("social").get(0)).put("url", "https://facebook.com/acme\n");
        assertThat(run(social)).anyMatch(e -> e.path().endsWith("social[0].url") && e.code().equals("URL_NOT_ALLOWED"));
        // controls in the middle are rejected too (plain text: the schema already did, now every string)
        ObjectNode mid = doc();
        ((ObjectNode) props(mid, "footer")).put("tagline", "a\u0007b");
        assertThat(run(mid)).anyMatch(e -> e.path().endsWith("props.tagline") && e.code().equals("UNSAFE_CONTENT"));
    }

    @Test
    void aRichTextBodyKeepsItsLegitimateNewlinesAndTabs() {
        ObjectNode d = doc();
        ObjectNode rt = (ObjectNode) d.get("pages").get("home").get(0);
        rt.put("type", "rich-text");
        rt.set("props", MAPPER.createObjectNode().put("body", "Line one\n\n- a\n- b\r\nTab\there\n"));
        assertThat(run(d)).as("body with \\n, \\r and \\t and a trailing newline is fine").noneMatch(e -> e.path().endsWith("props.body"));
    }

    /** THEME-05 (doc): the validator decodes entities when it checks a destination, the storefront keeps them literal. */
    @Test
    void anEntityEncodedAmpersandInALinkDestinationIsAccepted_andADecodedBadSchemeIsNot() {
        ObjectNode ok = doc();
        ((ObjectNode) props(ok, "story")).put("body", "[x](https://e.com/?a=1&amp;b=2)");
        assertThat(run(ok)).noneMatch(e -> e.path().endsWith("props.body"));
        ObjectNode bad = doc();
        ((ObjectNode) props(bad, "story")).put("body", "[x](&#106;avascript:alert(1))");
        assertThat(run(bad)).anyMatch(e -> e.path().endsWith("props.body"));
    }

    @Test
    void theContractExamplesStillValidate() {
        assertThat(run(acme())).isEmpty();
        assertThat(run(defaults())).isEmpty();
    }

    // ---------------------------------------------------------------- AC1

    private ObjectNode docWithRichTextSections(int home, int product, int charsEach, Consumer<ObjectNode> tweak) {
        ObjectNode d = acme();
        ObjectNode pages = (ObjectNode) d.get("pages");
        ArrayNode h = pages.putArray("home");
        ArrayNode p = pages.putArray("product");
        int n = 0;
        for (int i = 0; i < home + product; i++) {
            ObjectNode s = MAPPER.createObjectNode().put("id", "rt" + n).put("type", "rich-text").put("visible", true).put("order", n);
            s.set("props", MAPPER.createObjectNode().put("body", ("word ".repeat(charsEach)).substring(0, charsEach)));
            (i < home ? h : p).add(s);
            n++;
        }
        tweak.accept(d);
        return d;
    }

    /** 18 sections x 1000 code points: the 8000 budget runs out at the 9th body; only the first 8 are ever scanned. */
    @Test
    void theBudgetIsCountedBeforeTheCheckerAndTheCheckerIsSkippedOnceItIsExceeded() {
        ObjectNode d = docWithRichTextSections(12, 6, 1000, x -> { });
        List<ThemeFieldError> errors = run(d);
        assertThat(errors).anyMatch(e -> e.path().equals("pages.home[8].props.body") && e.code().equals("RICH_TEXT_BUDGET_EXCEEDED"));
        assertThat(errors).noneMatch(e -> e.path().equals("pages.home[7].props.body"));
        verify(validator, times(8)).checkRichText(anyString()); // 8 x 1000 = 8000 fits; bodies 9..18 are never scanned
    }

    @Test
    void everyBodyInsideTheBudgetIsStillChecked_hostileOnesAreRejected() {
        ObjectNode d = docWithRichTextSections(3, 0, 500, x -> ((ObjectNode) x.get("pages").get("home").get(1).get("props"))
                .put("body", "see [x](javascript:alert(1))"));
        List<ThemeFieldError> errors = run(d);
        assertThat(errors).anyMatch(e -> e.path().equals("pages.home[1].props.body") && e.code().equals("UNSAFE_CONTENT"));
        verify(validator, times(3)).checkRichText(anyString());
    }

    @Test
    void aSingleOverCapBodyInsideTheBudgetIsStillReportedTooLong() {
        ObjectNode d = docWithRichTextSections(1, 0, 5001, x -> { });
        assertThat(run(d)).anyMatch(e -> e.path().equals("pages.home[0].props.body") && e.code().equals("TOO_LONG"));
    }

    @Test
    void exactlyTheBudgetIsAccepted_oneCodePointMoreIsNot() {
        ObjectNode d = docWithRichTextSections(2, 0, 4000, x -> { });
        assertThat(run(d)).noneMatch(e -> e.code().equals("RICH_TEXT_BUDGET_EXCEEDED"));
        ObjectNode over = docWithRichTextSections(2, 0, 4000, x -> ((ObjectNode) x.get("pages").get("home").get(1).get("props"))
                .put("body", "w".repeat(4001)));
        assertThat(run(over)).anyMatch(e -> e.code().equals("RICH_TEXT_BUDGET_EXCEEDED") && e.path().equals("pages.home[1].props.body"));
    }

    /** The practical effect of the skip: 18 hostile 5000-char sections no longer cost 200-500 ms of checker work. */
    @Test
    void eighteenMaximalSectionsAreRejectedQuicklyWithoutScanningThemAll() {
        ObjectNode d = docWithRichTextSections(12, 6, 5000, x -> { });
        long t0 = System.nanoTime();
        List<ThemeFieldError> errors = run(d);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(errors).isNotEmpty();
        verify(validator, times(1)).checkRichText(anyString()); // only the first 5000 fits the 8000 budget
        assertThat(ms).as("validation time with the budget skip").isLessThan(2000);
    }
}
