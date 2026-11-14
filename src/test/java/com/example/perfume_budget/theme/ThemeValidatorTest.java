package com.example.perfume_budget.theme;

import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** THEME-02 AC3/AC4 at unit level: every negative case of the contract plus the XSS payloads. No Spring, no DB. */
class ThemeValidatorTest {

    ProductRepository products = mock(ProductRepository.class);
    CategoryRepository categories = mock(CategoryRepository.class);
    ThemeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ThemeValidator(MAPPER, products, categories, "shopfi-demo");
        // acme-full references products 12,15,... and categories 1,2,...: treat 1..100 as existing by default
        when(products.findExistingIds(anyCollection())).thenAnswer(i -> existing(i.getArgument(0)));
        when(categories.findExistingIds(anyCollection())).thenAnswer(i -> existing(i.getArgument(0)));
    }

    private static List<Long> existing(java.util.Collection<Long> ids) {
        return ids.stream().filter(id -> id <= 100).toList();
    }

    private List<ThemeFieldError> run(ObjectNode doc) {
        return validator.validate(doc, "acme", Set.of());
    }

    private void assertHas(List<ThemeFieldError> errors, String path, String code) {
        assertThat(errors).as(errors.toString()).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo(path);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.message()).isNotBlank();
        });
    }

    private ObjectNode mutated(Consumer<ObjectNode> m) {
        ObjectNode d = acme();
        m.accept(d);
        return d;
    }

    private ObjectNode props(ObjectNode doc, String id) {
        return (ObjectNode) section(doc, id).get("props");
    }

    // ---------------------------------------------------------------- positives

    @Test
    void contractExamplesAreValid() {
        assertThat(run(acme())).isEmpty();
        assertThat(run(defaults())).isEmpty();
        assertThat(run(example("bravo-minimal"))).isEmpty();
    }

    @Test
    void serverManagedFieldsAreIgnoredOnWrite() {
        ObjectNode d = acme();
        d.put("revision", "garbage");
        d.put("updatedAt", "not a date");
        assertThat(run(d)).isEmpty();
    }

    // ---------------------------------------------------------------- tokens

    @ParameterizedTest
    @ValueSource(strings = {"#fff", "#FFFFFFFF", "red", "rgb(0,0,0)", "var(--x)", "#12345g", "javascript:alert(1)", "#111827;}</style>"})
    void badColorsAreRejected(String bad) {
        ObjectNode d = mutated(x -> ((ObjectNode) x.get("tokens").get("colors")).put("accent", bad));
        List<ThemeFieldError> e = run(d);
        assertThat(e).anySatisfy(err -> assertThat(err.path()).isEqualTo("tokens.colors.accent"));
    }

    @Test
    void missingRequiredTokenIsRequired() {
        ObjectNode d = mutated(x -> ((ObjectNode) x.get("tokens").get("colors")).remove("primary"));
        assertHas(run(d), "tokens.colors.primary", "REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Comic Sans MS", "Inter, sans-serif", "x'; background:url(//evil)", "inter"})
    void fontsOutsideAllowListAreRejected(String font) {
        ObjectNode d = mutated(x -> ((ObjectNode) x.get("tokens").get("typography")).put("headingFont", font));
        assertHas(run(d), "tokens.typography.headingFont", "NOT_ALLOWED_VALUE");
    }

    @Test
    void baseSizeOutOfRangeAndWrongType() {
        assertHas(run(mutated(x -> ((ObjectNode) x.get("tokens").get("typography")).put("baseSizePx", 40))),
                "tokens.typography.baseSizePx", "OUT_OF_RANGE");
        assertHas(run(mutated(x -> ((ObjectNode) x.get("tokens").get("typography")).put("baseSizePx", "16"))),
                "tokens.typography.baseSizePx", "WRONG_TYPE");
    }

    @Test
    void radiusAndSpacingEnums() {
        assertHas(run(mutated(x -> ((ObjectNode) x.get("tokens")).put("radius", "huge"))), "tokens.radius", "NOT_ALLOWED_VALUE");
        assertHas(run(mutated(x -> ((ObjectNode) x.get("tokens")).put("spacing", "tight"))), "tokens.spacing", "NOT_ALLOWED_VALUE");
    }

    @Test
    void lowContrastIsRejectedWithForegroundPath() {
        ObjectNode d = mutated(x -> ((ObjectNode) x.get("tokens").get("colors")).put("onPrimary", "#222222")
                .put("primary", "#111827"));
        assertHas(run(d), "tokens.colors.onPrimary", "CONTRAST_TOO_LOW");
    }

    @Test
    void unknownTokenPropertyAndUnknownRootProperty() {
        assertHas(run(mutated(x -> ((ObjectNode) x.get("tokens")).put("customCss", "body{}"))), "tokens.customCss", "UNKNOWN_PROPERTY");
        assertHas(run(mutated(x -> x.put("script", "x"))), "script", "UNKNOWN_PROPERTY");
    }

    @Test
    void schemaVersionMustBeSupported() {
        assertHas(run(mutated(x -> x.put("schemaVersion", 2))), "schemaVersion", "UNSUPPORTED_SCHEMA_VERSION");
        assertHas(run(mutated(x -> x.remove("schemaVersion"))), "schemaVersion", "REQUIRED");
    }

    @Test
    void oneOfBranchNoiseIsCollapsedToSpecificErrors() {
        ObjectNode d = mutated(x -> ((ObjectNode) x.get("assets").get("logo")).put("url", "http://x").remove("alt"));
        List<ThemeFieldError> e = run(d);
        assertHas(e, "assets.logo.url", "URL_NOT_ALLOWED");
        assertHas(e, "assets.logo.alt", "REQUIRED");
        assertThat(e).noneMatch(err -> err.path().equals("assets.logo"));
        assertHas(run(mutated(x -> ((ObjectNode) x.get("assets")).put("logo", 5))), "assets.logo", "WRONG_TYPE");
    }

    @Test
    void notAnObjectIsRejected() {
        assertThat(validator.validateStructure(json("[1]"))).extracting(ThemeFieldError::code).containsExactly("WRONG_TYPE");
        assertThat(validator.validateStructure(null)).extracting(ThemeFieldError::code).containsExactly("WRONG_TYPE");
    }

    // ---------------------------------------------------------------- sections

    @Test
    void unknownSectionTypeAndWrongSlot() {
        ObjectNode d = mutated(x -> ((ObjectNode) section(x, "promo")).put("type", "carousel"));
        assertThat(run(d)).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo("UNKNOWN_SECTION_TYPE");
            assertThat(e.path()).endsWith(".type");
        });
        ObjectNode d2 = mutated(x -> ((ArrayNode) x.get("pages").get("product")).add(json(
                "{\"id\":\"cat2\",\"type\":\"category-grid\",\"props\":{},\"visible\":true,\"order\":5}")));
        assertThat(run(d2)).anySatisfy(e -> assertThat(e.code()).isEqualTo("SECTION_NOT_ALLOWED_IN_SLOT"));
    }

    @Test
    void tooManySectionsPerSlot() {
        ObjectNode d = defaults();
        ArrayNode home = (ArrayNode) d.get("pages").get("home");
        for (int i = 0; i < 13; i++) {
            home.add(json("{\"id\":\"b" + i + "\",\"type\":\"banner\",\"props\":{\"message\":\"hi\"},\"visible\":true,\"order\":" + (10 + i) + "}"));
        }
        assertHas(run(d), "pages.home", "TOO_MANY_SECTIONS");
        ObjectNode d2 = defaults();
        ((ArrayNode) d2.get("pages").get("footer")).add(json("{\"id\":\"f2\",\"type\":\"footer\",\"props\":{},\"visible\":true,\"order\":1}"));
        assertHas(run(d2), "pages.footer", "TOO_MANY_SECTIONS");
    }

    @Test
    void hugeSectionArrayIsBoundedAndFast() {
        ObjectNode d = defaults();
        ArrayNode home = (ArrayNode) d.get("pages").get("home");
        for (int i = 0; i < 5000; i++) {
            home.add(json("{\"id\":\"b" + i + "\",\"type\":\"banner\",\"props\":{},\"visible\":true,\"order\":1}"));
        }
        List<ThemeFieldError> e = run(d);
        assertThat(e.size()).isLessThanOrEqualTo(ThemeValidator.MAX_ERRORS);
        assertHas(e, "pages.home", "TOO_MANY_SECTIONS");
    }

    @Test
    void duplicateIdsAndOrders() {
        ObjectNode d = defaults();
        ((ObjectNode) d.get("pages").get("home").get(1)).put("id", "hero");
        assertThat(run(d)).anySatisfy(e -> assertThat(e.code()).isEqualTo("DUPLICATE_SECTION_ID"));
        ObjectNode d2 = defaults();
        ((ObjectNode) d2.get("pages").get("home").get(1)).put("order", 0);
        assertThat(run(d2)).anySatisfy(e -> assertThat(e.code()).isEqualTo("DUPLICATE_ORDER"));
        ObjectNode d3 = defaults(); // ids are unique across slots too
        ((ObjectNode) d3.get("pages").get("footer").get(0)).put("id", "hero");
        assertThat(run(d3)).anySatisfy(e -> assertThat(e.code()).isEqualTo("DUPLICATE_SECTION_ID"));
    }

    @Test
    void sectionShapeErrors() {
        assertThat(run(mutated(x -> props(x, "hero").remove("heading"))))
                .anySatisfy(e -> { assertThat(e.code()).isEqualTo("REQUIRED"); assertThat(e.path()).endsWith("props.heading"); });
        assertThat(run(mutated(x -> props(x, "hero").put("heading", "x".repeat(81)))))
                .anySatisfy(e -> assertThat(e.code()).isEqualTo("TOO_LONG"));
        assertThat(run(mutated(x -> props(x, "hero").put("onclick", "alert(1)"))))
                .anySatisfy(e -> assertThat(e.code()).isEqualTo("UNKNOWN_PROPERTY"));
        assertThat(run(mutated(x -> props(x, "hero").put("overlayOpacity", 0.9))))
                .anySatisfy(e -> assertThat(e.code()).isEqualTo("OUT_OF_RANGE"));
        assertThat(run(mutated(x -> section(x, "hero").put("order", 100))))
                .anySatisfy(e -> assertThat(e.code()).isEqualTo("OUT_OF_RANGE"));
        assertThat(run(mutated(x -> section(x, "hero").put("visible", "yes"))))
                .anySatisfy(e -> assertThat(e.code()).isEqualTo("WRONG_TYPE"));
        assertThat(run(mutated(x -> props(x, "hero").put("ctaHref", "/x").remove("ctaLabel"))))
                .anySatisfy(e -> assertThat(e.code()).isEqualTo("REQUIRED"));
    }

    @Test
    void featuredProductsSourceRules() {
        ObjectNode manualNoIds = mutated(x -> { props(x, "pick").remove("productIds"); });
        assertThat(run(manualNoIds)).isNotEmpty();
        ObjectNode featuredWithIds = mutated(x -> props(x, "pick").put("source", "featured"));
        assertThat(run(featuredWithIds)).isNotEmpty();
    }

    @Test
    void sectionsMustBeObjectsWithTypes() {
        ObjectNode d = defaults();
        ((ArrayNode) d.get("pages").get("home")).add("nope");
        ((ArrayNode) d.get("pages").get("home")).add(json("{\"id\":\"zz\"}"));
        List<ThemeFieldError> e = run(d);
        assertHas(e, "pages.home[3]", "WRONG_TYPE");
        assertHas(e, "pages.home[4].type", "REQUIRED");
    }

    @Test
    void errorListIsCappedAtFifty() {
        ObjectNode d = defaults();
        ArrayNode home = (ArrayNode) d.get("pages").get("home");
        home.removeAll();
        for (int i = 0; i < 12; i++) {
            home.add(json("{\"id\":\"b" + i + "\",\"type\":\"banner\",\"props\":{\"message\":\"\",\"a\":1,\"b\":2,\"c\":3,\"d\":4},\"visible\":1,\"order\":-1}"));
        }
        assertThat(run(d).size()).isLessThanOrEqualTo(50).isGreaterThan(20);
    }

    @Test
    void deeplyNestedDocumentIsRejectedWithoutRecursion() {
        ObjectNode d = defaults();
        ObjectNode cur = d;
        for (int i = 0; i < 500; i++) {
            ObjectNode next = MAPPER.createObjectNode();
            cur.set("x", next);
            cur = next;
        }
        assertThat(validator.validateStructure(d)).extracting(ThemeFieldError::code).contains("OUT_OF_RANGE");
    }

    // ---------------------------------------------------------------- XSS in text fields

    static final String[] XSS = {
            "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "\"><svg onload=alert(1)>", "a<b",
            "x>y", "line\u0000break", "ctrl\u0007char", "tab\there"
    };

    @Test
    void plainTextFieldsRejectMarkupAndControlCharsEverywhere() {
        for (String payload : XSS) {
            assertUnsafe(x -> props(x, "hero").put("heading", payload), "props.heading", payload);
            assertUnsafe(x -> props(x, "hero").put("subheading", payload), "props.subheading", payload);
            assertUnsafe(x -> props(x, "hero").put("imageAlt", payload), "props.imageAlt", payload);
            assertUnsafe(x -> props(x, "hero").put("ctaLabel", payload), "props.ctaLabel", payload);
            assertUnsafe(x -> props(x, "pick").put("title", payload), "props.title", payload);
            assertUnsafe(x -> props(x, "promo").put("message", payload), "props.message", payload);
            assertUnsafe(x -> props(x, "promo").put("linkLabel", payload), "props.linkLabel", payload);
            assertUnsafe(x -> props(x, "story").put("title", payload), "props.title", payload);
            assertUnsafe(x -> ((ObjectNode) x.get("assets").get("logo")).put("alt", payload), "assets.logo.alt", payload);
            assertUnsafe(x -> props(x, "footer").put("tagline", payload), "props.tagline", payload);
            assertUnsafe(x -> props(x, "footer").put("copyrightText", payload), "props.copyrightText", payload);
            assertUnsafe(x -> ((ObjectNode) props(x, "footer").get("linkGroups").get(0)).put("title", payload), "linkGroups[0].title", payload);
            assertUnsafe(x -> ((ObjectNode) props(x, "footer").get("linkGroups").get(0).get("links").get(0)).put("label", payload), "links[0].label", payload);
        }
    }

    private void assertUnsafe(Consumer<ObjectNode> m, String pathSuffix, String payload) {
        List<ThemeFieldError> e = run(mutated(m));
        assertThat(e).as(payload + " @ " + pathSuffix + " -> " + e).anySatisfy(err -> {
            assertThat(err.path()).endsWith(pathSuffix);
            assertThat(err.code()).isEqualTo("UNSAFE_CONTENT");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>", "vbscript:x", "//evil.com/x",
            "http://insecure.example", "../up", "relative/path", "/ok\"onmouseover=\"x", "https://x.com/a b", "ftp://x.com", "file:///etc/passwd", "\\\\host\\share"})
    void linkHrefsOutsideAllowListAreRejected(String href) {
        assertThat(run(mutated(x -> props(x, "hero").put("ctaHref", href))))
                .anySatisfy(e -> { assertThat(e.path()).endsWith("props.ctaHref"); assertThat(e.code()).isEqualTo("URL_NOT_ALLOWED"); });
        assertThat(run(mutated(x -> props(x, "promo").put("linkHref", href))))
                .anySatisfy(e -> assertThat(e.path()).endsWith("props.linkHref"));
        assertThat(run(mutated(x -> ((ObjectNode) props(x, "footer").get("linkGroups").get(0).get("links").get(0)).put("href", href))))
                .anySatisfy(e -> assertThat(e.path()).endsWith("href"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/x", "http://www.facebook.com/x", "https://facebook.com.evil.com/x",
            "javascript:alert(1)", "https://notfacebook.com/x", "https://facebook.com@evil.com"})
    void socialUrlsMustBeAllowListedHosts(String url) {
        assertThat(run(mutated(x -> ((ObjectNode) props(x, "footer").get("social").get(0)).put("url", url))))
                .anySatisfy(e -> assertThat(e.path()).endsWith("social[0].url"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/a.png", "http://res.cloudinary.com/x/image/upload/a.png",
            "data:image/svg+xml;base64,PHN2Zz4=", "javascript:alert(1)", "https://res.cloudinary.com/shopfi-demo/image/upload/x.svg\"onerror=\"x",
            "https://res.cloudinary.com.evil.com/shopfi-demo/image/upload/a.png"})
    void imageUrlsMustBeCloudinary(String url) {
        assertThat(run(mutated(x -> ((ObjectNode) x.get("assets").get("logo")).put("url", url))))
                .anySatisfy(e -> assertThat(e.path()).isEqualTo("assets.logo.url"));
        assertThat(run(mutated(x -> props(x, "hero").put("imageUrl", url))))
                .anySatisfy(e -> assertThat(e.path()).endsWith("props.imageUrl"));
    }

    // ---------------------------------------------------------------- rich text

    private List<ThemeFieldError> body(String md) {
        return run(mutated(x -> props(x, "story").put("body", md)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>", "hello <b>bold</b>", "<img src=x onerror=alert(1)>", "<https://autolink.example>",
            "![x](https://res.cloudinary.com/a/image/upload/b.png)", "![x](javascript:alert(1))",
            "[x](javascript:alert(1))", "[x](JaVaScRiPt:alert(1))", "[x]( javascript:alert(1))", "[x](JaVa\nScRiPt:alert(1))",
            "[x](&#106;avascript:alert(1))", "[x](&#x6A;avascript:alert(1))", "[x](javascript&colon;alert(1))", "[x](&Tab;javascript:alert(1))",
            "[x](data:text/html;base64,PHNjcmlwdD4=)", "[x](DATA:text/html,hi)", "[x](vbscript:msgbox(1))", "[x](file:///etc/passwd)",
            "[x](blob:https://a/b)", "[x](http://insecure.example)", "[x](//evil.example)", "[x](ftp://x)", "[x](../up)", "[x](relative)",
            "[x][r]\n\n[r]: javascript:alert(1)", "[x](/ok \"title\")", "&lt;script&gt;alert(1)&lt;/script&gt;", "&#60;img src=x&#62;",
            "```\ncode\n```", "    indented code", "> quote", "---", "# h1", "#### h4", "- a\n  - nested", "| a | b |\n|---|---|\n| <x> | y |",
            "[x](/a)<script>", "\u0000", "bell\u0007"})
    void unsafeRichTextIsRejected(String md) {
        List<ThemeFieldError> e = body(md);
        assertThat(e).as(md + " -> " + e).anySatisfy(err -> {
            assertThat(err.path()).endsWith("props.body");
            assertThat(err.code()).isIn("UNSAFE_CONTENT");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Plain paragraph.", "## Heading\n\nText with **bold** and *italic*.", "- one\n- two\n\n1. a\n2. b",
            "[home](/) [ext](https://example.com/x?a=b&c=d) [mail](mailto:a@b.co) [call](tel:+233201234567)",
            "line one  \nline two", "Price 5 & 6, a * b"})
    void safeRichTextIsAccepted(String md) {
        assertThat(body(md)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"- - - - x", "1. 1. 1. 1. x", "* * * * *", "- a\n        - b\n", "\t\t- x"})
    void deeplyNestedMarkdownIsRejectedAsTooDeep(String md) {
        assertHas(body(md), "pages.home[5].props.body", "RICH_TEXT_TOO_DEEP");
    }

    @Test
    void nestedMarkerBombIsRejectedQuicklyWithoutParsing() {
        for (String bomb : new String[]{"- ".repeat(1200), "- ".repeat(2500), "> ".repeat(2500), "  ".repeat(2400) + "x",
                ("- ".repeat(1200) + "\n").repeat(2)}) {
            long t0 = System.nanoTime();
            List<ThemeFieldError> e = body(bomb);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(e).anySatisfy(err -> assertThat(err.code()).isIn("RICH_TEXT_TOO_DEEP", "TOO_LONG", "UNSAFE_CONTENT")); // ">" is also caught by the schema
            assertThat(ms).as("validator time for bomb").isLessThan(500);
        }
        long t0 = System.nanoTime();
        assertThat(RichTextChecker.check("- ".repeat(1200))).isNotNull().extracting(RichTextChecker.Problem::code).isEqualTo("RICH_TEXT_TOO_DEEP");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(100);
    }

    private static final String EMOJI_2600 = "\uD83D\uDE00".repeat(2600); // 2600 code points, 5200 UTF-16 units

    @ParameterizedTest
    @ValueSource(strings = {"\n\n[x](&#106;avascript:alert(1))", "\n\n[x][r]\n\n[r]: javascript:alert(1)", "\n\n[x](&#x6A;avascript:alert(1))",
            "\n- - - - - - x", "\n\n[x](javascript&colon;alert(1))"})
    void checkerStillRunsWhenBodyIsLongInUtf16UnitsButUnderTheCodePointCap(String tail) {
        String md = EMOJI_2600 + tail;
        assertThat(md.length()).isGreaterThan(5000);
        assertThat(md.codePointCount(0, md.length())).isLessThanOrEqualTo(5000);
        assertThat(body(md)).as(tail).anySatisfy(err -> {
            assertThat(err.path()).isEqualTo("pages.home[5].props.body");
            assertThat(err.code()).isIn("UNSAFE_CONTENT", "RICH_TEXT_TOO_DEEP");
        });
    }

    @Test
    void harmlessLongEmojiBodyUnderTheCodePointCapIsAccepted() {
        assertThat(body(EMOJI_2600)).isEmpty();
    }

    @Test
    void bodyOverTheCodePointCapIsRejectedExplicitlyNotSkipped() {
        String over = EMOJI_2600 + "a".repeat(2401); // 5001 code points
        assertThat(RichTextChecker.check(over)).isNotNull().extracting(RichTextChecker.Problem::code).isEqualTo("TOO_LONG");
        assertThat(body(over)).anySatisfy(e -> assertThat(e.code()).isEqualTo("TOO_LONG"));
        // nothing about length lets the hostile tail through, and very long hostile bodies are still judged by the checker
        assertThat(RichTextChecker.check("- ".repeat(20_000))).extracting(RichTextChecker.Problem::code).isEqualTo("RICH_TEXT_TOO_DEEP");
        assertThat(RichTextChecker.check("a".repeat(60_000) + "[x](&#106;avascript:alert(1))")).isNotNull();
        assertThat(RichTextChecker.check(EMOJI_2600 + "a".repeat(2400))).isNull(); // exactly 5000 code points
    }

    @Test
    void sixtyBlockLinesAreAllowedSixtyOneAreNot() {
        assertThat(RichTextChecker.check("- x\n".repeat(60))).isNull();
        for (String line : new String[]{"- x\n", "1. x\n", "-\n", "* x\r\n", "+ x\r"}) {
            assertThat(RichTextChecker.check(line.repeat(61))).as(line).isNotNull()
                    .extracting(RichTextChecker.Problem::code).isEqualTo("RICH_TEXT_TOO_MANY_LINES");
        }
        assertHas(body("- x\n".repeat(61)), "pages.home[5].props.body", "RICH_TEXT_TOO_MANY_LINES");
        assertThat(body("- x\n".repeat(60))).isEmpty();
    }

    @Test
    void carriageReturnLineEndingsCannotHideNestingOrLines() {
        assertThat(RichTextChecker.check("ok\r- - - - x")).extracting(RichTextChecker.Problem::code).isEqualTo("RICH_TEXT_TOO_DEEP");
        assertThat(RichTextChecker.check("ok\r\n- - - - x")).extracting(RichTextChecker.Problem::code).isEqualTo("RICH_TEXT_TOO_DEEP");
        assertThat(RichTextChecker.maxNesting("a\r- - - - x\r")).isEqualTo(4);
    }

    private ObjectNode withRichTextBodies(String... bodies) {
        ObjectNode d = defaults();
        ArrayNode home = (ArrayNode) d.get("pages").get("home");
        for (int i = 0; i < bodies.length; i++) {
            ObjectNode sec = (ObjectNode) json("{\"id\":\"rt" + i + "\",\"type\":\"rich-text\",\"props\":{},\"visible\":true,\"order\":" + (50 + i) + "}");
            ((ObjectNode) sec.get("props")).put("body", bodies[i]);
            home.add(sec);
        }
        return d;
    }

    @Test
    void totalMarkdownBudgetPerDocumentIsEnforcedInCodePoints() {
        String four = "a".repeat(4000);
        assertThat(run(withRichTextBodies(four, four))).as("exactly 8000 is fine").isEmpty();
        // third body (3 sections -> indexes 3,4,5 in home) pushes the total over 8000
        List<ThemeFieldError> e = run(withRichTextBodies(four, four, "b"));
        assertHas(e, "pages.home[5].props.body", "RICH_TEXT_BUDGET_EXCEEDED");
        assertThat(e).noneMatch(x -> x.path().equals("pages.home[3].props.body") || x.path().equals("pages.home[4].props.body"));
        // astral characters count once each
        String emoji = "\uD83D\uDE00".repeat(4000);
        assertThat(run(withRichTextBodies(emoji, emoji))).isEmpty();
        assertHas(run(withRichTextBodies(emoji, emoji, "x")), "pages.home[5].props.body", "RICH_TEXT_BUDGET_EXCEEDED");
    }

    @Test
    void nestingUpToThreeLevelsIsNotFlaggedAsTooDeep() {
        assertThat(RichTextChecker.maxNesting("- one\n- two")).isEqualTo(1);
        assertThat(RichTextChecker.maxNesting("1. a\n  - b")).isEqualTo(2);
        assertThat(RichTextChecker.maxNesting("plain 5 - 3 and *bold* 1.5")).isZero();
    }

    @Test
    void richTextTooLongIsRejected() {
        assertHas(body("a".repeat(5001)).stream().filter(e -> e.code().equals("TOO_LONG")).toList(),
                "pages.home[5].props.body", "TOO_LONG");
    }

    // ---------------------------------------------------------------- ownership and references

    @Test
    void foreignCloudinaryUrlsAreNotOwned() {
        ObjectNode other = mutated(x -> ((ObjectNode) x.get("assets").get("logo"))
                .put("url", "https://res.cloudinary.com/shopfi-demo/image/upload/v1/shopfi/bravo/branding/logo.png"));
        assertHas(run(other), "assets.logo.url", "ASSET_URL_NOT_OWNED");
        ObjectNode otherCloud = mutated(x -> ((ObjectNode) x.get("assets").get("logo"))
                .put("url", "https://res.cloudinary.com/someone-else/image/upload/v1/shopfi/acme/branding/logo.png"));
        assertHas(run(otherCloud), "assets.logo.url", "ASSET_URL_NOT_OWNED");
        ObjectNode outside = mutated(x -> ((ObjectNode) x.get("assets").get("logo"))
                .put("url", "https://res.cloudinary.com/shopfi-demo/image/upload/v1/images/random.png"));
        assertThat(run(outside)).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo("assets.logo.url");
            assertThat(e.code()).isIn("URL_NOT_ALLOWED", "ASSET_URL_NOT_OWNED"); // the tightened schema pattern fires first
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1/shopfi/acme/branding/../../bravo/branding/logo.png", "v1/shopfi/acme/branding/sub/logo.png",
            "v1/shopfi/acme/branding/%2e%2e/logo.png", "v1/shopfi/acmeX/branding/logo.png", "v1/evil/shopfi/acme/branding/logo.png",
            "shopfi/acme/branding/", "shopfi/acme/branding"})
    void traversalAndLookalikeFoldersAreNotOwned(String tail) {
        assertThat(validator.ownedByTenant("https://res.cloudinary.com/shopfi-demo/image/upload/" + tail, "acme")).isFalse();
    }

    private static final String UP = "https://res.cloudinary.com/shopfi-demo/image/upload/";

    @ParameterizedTest
    @ValueSource(strings = {"v12/shopfi/acme/branding/logo.png", "shopfi/acme/branding/logo.png", "v1/shopfi/acme/branding/background.jpg",
            "v1/shopfi/acme/branding/background.jpeg", "v1/shopfi/acme/branding/section-hero.webp", "v1/shopfi/acme/branding/section-promo-2.png",
            "v1731500000/shopfi/acme/branding/logo-0123456789abcdef.png", "v1/shopfi/acme/branding/section-hero-0123456789abcdef.jpg"})
    void exactBrandingUrlsAreOwned(String tail) {
        assertThat(validator.ownedByTenant(UP + tail, "acme")).as(tail).isTrue();
        assertThat(validator.ownedByTenant(UP + tail, "bravo")).as(tail + " for another tenant").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "l_fetch:aHR0cHM6Ly9ldmlsLmNvbS94LnBuZw/shopfi/acme/branding/logo.png",
            "l_text:Arial_200:BUY_NOW/shopfi/acme/branding/logo.png", "fl_attachment/shopfi/acme/branding/logo.png",
            "f_auto,q_auto/v12/shopfi/acme/branding/logo.png", "w_100/shopfi/acme/branding/logo.png", "h_10/v1/shopfi/acme/branding/logo.png",
            "x_5/shopfi/acme/branding/logo.png", "y_5/shopfi/acme/branding/logo.png", "e_blur:100/shopfi/acme/branding/logo.png",
            "l_fetch:aaa/v1/shopfi/acme/branding/logo.png", "v1/v2/shopfi/acme/branding/logo.png", "v1/fl_attachment/shopfi/acme/branding/logo.png",
            "v1/shopfi/acme/branding/logo.svg", "v1/shopfi/acme/branding/logo", "v1/shopfi/acme/branding/logo.gif",
            "v1/shopfi/acme/branding/logo.png.exe", "v1/shopfi/acme/branding/Logo.png", "v1/shopfi/acme/branding/avatar.png",
            "v1/shopfi/acme/branding/.png", "v1/shopfi/acme/branding/logo-xyz.png", "v1/shopfi/acme/branding/section-.png",
            "v1/shopfi/acme/branding/logo.png?x=1", "v1/shopfi/acme/branding/logo.png#a", "v1/shopfi/acme/branding/logo.png\n",
            "v/shopfi/acme/branding/logo.png", "va1/shopfi/acme/branding/logo.png", "v1234567890123/shopfi/acme/branding/logo.png"})
    void transformationsSvgAndOddFileNamesAreNotOwned(String tail) {
        assertThat(validator.ownedByTenant(UP + tail, "acme")).as(tail).isFalse();
    }

    @Test
    void ownershipRejectsNullTenantAndBadSlugs() {
        assertThat(validator.ownedByTenant(UP + "shopfi/acme/branding/logo.png", null)).isFalse();
        assertThat(validator.ownedByTenant(UP + "shopfi/acme/branding/logo.png", "AC ME")).isFalse();
    }

    @Test
    void schemaPatternRejectsTransformationUrlsToo() {
        for (String tail : new String[]{"l_fetch:aHR0/shopfi/acme/branding/logo.png", "f_auto/v1/shopfi/acme/branding/logo.png",
                "v1/shopfi/acme/branding/logo.svg", "v1/images/random.png", "v1/shopfi/acme/branding/logo.png?x=1"}) {
            ObjectNode d = mutated(x -> ((ObjectNode) x.get("assets").get("logo")).put("url", UP + tail));
            assertThat(validator.validateStructure(d)).as(tail).anySatisfy(e -> assertThat(e.path()).isEqualTo("assets.logo.url"));
        }
        ObjectNode ok = mutated(x -> ((ObjectNode) x.get("assets").get("logo")).put("url", UP + "v1/shopfi/acme/branding/logo-0123456789abcdef.png"));
        assertThat(validator.validateStructure(ok)).isEmpty();
    }

    @Test
    void storedUrlsAreAlwaysAcceptedEvenIfNotOwned() {
        String legacy = "https://res.cloudinary.com/shopfi-demo/image/upload/v1/shopfi/bravo/branding/logo.png"; // schema-valid, other tenant
        ObjectNode d = mutated(x -> ((ObjectNode) x.get("assets").get("logo")).put("url", legacy));
        assertThat(validator.validate(d, "acme", Set.of(legacy))).isEmpty();
        assertHas(validator.validate(d, "acme", Set.of()), "assets.logo.url", "ASSET_URL_NOT_OWNED");
    }

    @Test
    void unknownProductAndCategoryReferencesAreReportedPerPath() {
        ObjectNode d = mutated(x -> {
            ((ArrayNode) props(x, "pick").get("productIds")).add(9999);
            ((ArrayNode) props(x, "cats").get("categoryIds")).add(8888);
        });
        List<ThemeFieldError> e = run(d);
        assertThat(e).extracting(ThemeFieldError::code).containsOnly("UNKNOWN_REFERENCE");
        assertThat(e).extracting(ThemeFieldError::path).anyMatch(p -> p.contains("productIds["))
                .anyMatch(p -> p.contains("categoryIds["));
        ObjectNode catSource = mutated(x -> ((ObjectNode) section(x, "related")).with("props").put("categoryId", 777));
        assertThat(run(catSource)).anySatisfy(err -> {
            assertThat(err.code()).isEqualTo("UNKNOWN_REFERENCE");
            assertThat(err.path()).endsWith("props.categoryId");
        });
    }

    @Test
    void referenceChecksSkipNonNumericGarbageWithoutTouchingTheDatabaseWithJunk() {
        ObjectNode d = mutated(x -> ((ArrayNode) props(x, "pick").get("productIds")).add("'; drop table products;--"));
        assertThat(run(d)).anySatisfy(e -> assertThat(e.code()).isEqualTo("WRONG_TYPE"));
        assertThat(run(d)).extracting(ThemeFieldError::code).doesNotContain("UNKNOWN_REFERENCE");
    }

    @Test
    void contrastHelperMatchesKnownValues() {
        assertThat(ThemeValidator.contrastRatio("#000000", "#ffffff")).isBetween(20.99, 21.01);
        assertThat(ThemeValidator.contrastRatio("#777777", "#ffffff")).isBetween(4.4, 4.5);
        List<String> unused = new ArrayList<>();
        assertThat(unused).isEmpty();
    }
}
