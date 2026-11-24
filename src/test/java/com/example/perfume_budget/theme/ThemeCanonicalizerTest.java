package com.example.perfume_budget.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SF-BE-01 AC2/AC3/AC4: PUT canonicalization and the GET legacy resolver (pure functions, no Spring). */
class ThemeCanonicalizerTest {

    final ThemeCatalog catalog = new ThemeCatalog(MAPPER);
    final ThemeCanonicalizer canon = new ThemeCanonicalizer(catalog, MAPPER);

    /** What the tenant currently holds, resolved: an untouched default (morrow). */
    ObjectNode currentMorrow() {
        return canon.resolveStored(defaults(), defaults());
    }

    ObjectNode legacyCurrent() {
        return canon.resolveStored(acme(), defaults());
    }

    ObjectNode run(ObjectNode submitted, JsonNode current) {
        return canon.canonicalize(submitted, current).document();
    }

    ObjectNode hero(ObjectNode canonical) {
        return (ObjectNode) canonical.at("/pages/home/0");
    }

    // ------------------------------------------------------------------ PUT: dropped vs kept

    @Test
    void forbiddenFieldsAreDropped_brandingFieldsKept() {
        ObjectNode in = acme();
        in.put("themeId", "ocean");
        ObjectNode out = run(in, currentMorrow());

        // hero + the announcement strip survive (owner decision 2026-11-24); every other section type is dropped
        assertThat(out.at("/pages/home")).hasSize(2);
        assertThat(out.at("/pages/home/1/id").asText()).isEqualTo("announcement");
        ObjectNode h = hero(out);
        assertThat(h.get("id").asText()).isEqualTo("hero");
        assertThat(h.get("type").asText()).isEqualTo("hero");
        assertThat(h.get("visible").asBoolean()).isTrue();
        assertThat(h.get("order").asInt()).isZero();
        assertThat(out.at("/pages/product")).isEmpty();
        assertThat(out.at("/pages/footer")).hasSize(1);
        assertThat(out.at("/assets/background").isNull()).isTrue();
        // branding kept
        JsonNode submittedHero = section(in, "hero").get("props");
        assertThat(h.at("/props/heading")).isEqualTo(submittedHero.get("heading"));
        assertThat(h.at("/props/subheading")).isEqualTo(submittedHero.get("subheading"));
        assertThat(h.at("/props/imageUrl")).isEqualTo(submittedHero.get("imageUrl"));
        assertThat(h.at("/props/imageAlt")).isEqualTo(submittedHero.get("imageAlt"));
        assertThat(h.at("/props/ctaLabel")).isEqualTo(submittedHero.get("ctaLabel"));
        assertThat(out.at("/assets/logo")).isEqualTo(in.at("/assets/logo"));
    }

    @Test
    void heroNonBrandingPropsAreForced() {
        ObjectNode in = acme();
        ObjectNode props = (ObjectNode) section(in, "hero").get("props");
        props.put("alignment", "right").put("overlayOpacity", 0.7).put("ctaHref", "https://evil.example/x").put("bogusProp", 1);
        props.put("ctaLabel", "Shop");
        ObjectNode h = hero(run(in, currentMorrow()));
        assertThat(h.at("/props/alignment").asText()).isEqualTo("left");
        assertThat(h.at("/props/ctaHref").asText()).isEqualTo("/products");
        assertThat(h.at("/props").has("overlayOpacity")).isFalse();
        assertThat(h.at("/props").has("bogusProp")).isFalse();
        assertThat(h.at("/props/ctaLabel").asText()).isEqualTo("Shop");
    }

    @Test
    void ctaHrefWithoutLabelIsDropped_labelWithoutHrefGetsTheFixedHref() {
        ObjectNode in = defaults();
        ObjectNode props = (ObjectNode) section(in, "hero").get("props");
        props.put("ctaHref", "/somewhere");
        assertThat(hero(run(in, currentMorrow())).at("/props").has("ctaHref")).isFalse();
        props.remove("ctaHref");
        props.put("ctaLabel", "Go");
        assertThat(hero(run(in, currentMorrow())).at("/props/ctaHref").asText()).isEqualTo("/products");
    }

    @Test
    void noHeroSubmitted_homeIsEmpty_heroPickedFromAnyPosition() {
        ObjectNode in = defaults();
        ((ArrayNode) in.at("/pages/home")).removeAll();
        assertThat(run(in, currentMorrow()).at("/pages/home")).isEmpty();
        // acme has its hero second (after a banner) and a second hero never wins
        ObjectNode acme = acme();
        ArrayNode home = (ArrayNode) acme.at("/pages/home");
        ObjectNode second = ((ObjectNode) section(acme, "hero")).deepCopy();
        second.put("id", "hero2");
        ((ObjectNode) second.get("props")).put("heading", "Second hero");
        home.add(second);
        ObjectNode canonical = run(acme, currentMorrow());
        assertThat(canonical.at("/pages/home")).hasSize(2);
        assertThat(canonical.toString()).doesNotContain("hero2").doesNotContain("Second hero");
        assertThat(hero(canonical).at("/props/heading")).isEqualTo(section(acme, "hero").at("/props/heading"));
    }

    @Test
    void heroValidationErrorsKeepTheirUsualPathBecauseHeroIsHome0() {
        ObjectNode in = defaults();
        ((ObjectNode) section(in, "hero").get("props")).remove("heading");
        ObjectNode out = run(in, currentMorrow());
        assertThat(hero(out).at("/props").has("heading")).isFalse(); // schema then reports pages.home[0].props.heading REQUIRED
    }

    @Test
    void hostileTokensAreIgnored_themeComesFromTheCatalog() {
        ObjectNode in = acme();
        in.put("themeId", "plum");
        ObjectNode tokens = (ObjectNode) in.get("tokens");
        ((ObjectNode) tokens.get("colors")).put("primary", "not-a-colour").put("text", "#ffffff");
        tokens.put("radius", "bogus").put("extra", true);
        ObjectNode out = run(in, currentMorrow());
        assertThat(out.get("tokens")).isEqualTo(catalog.tokens("plum"));
        assertThat(out.get("themeId").asText()).isEqualTo("plum");
    }

    @Test
    void tokensMayBeOmittedAltogether() {
        ObjectNode in = defaults();
        in.remove("tokens");
        in.put("themeId", "sand");
        assertThat(run(in, currentMorrow()).get("tokens")).isEqualTo(catalog.tokens("sand"));
    }

    // ------------------------------------------------------------------ themeId

    @Test
    void themeIdAbsentKeepsTheCurrentTheme_catalogOrLegacy() {
        ObjectNode current = canon.resolveStored(defaults(), defaults()); // morrow
        ObjectNode in = acme();
        in.get("tokens").get("colors"); // old client sends its own tokens
        ObjectNode out = run(in, current);
        assertThat(out.get("themeId").asText()).isEqualTo("morrow");
        assertThat(out.get("tokens")).isEqualTo(catalog.tokens("morrow"));

        ObjectNode legacy = legacyCurrent();
        ObjectNode out2 = run(defaults(), legacy);
        assertThat(out2.get("themeId").asText()).isEqualTo("custom-legacy");
        assertThat(out2.get("tokens")).isEqualTo(legacy.get("tokens")).isNotEqualTo(defaults().get("tokens"));
    }

    @Test
    void jsonNullThemeIdIsTreatedAsAbsent() {
        ObjectNode in = defaults();
        in.putNull("themeId");
        assertThat(run(in, currentMorrow()).get("themeId").asText()).isEqualTo("morrow");
    }

    @Test
    void unknownReservedOrMalformedThemeIdIsRejectedWithAStableCode() {
        for (String bad : new String[]{"nope", "custom-legacy", "Morrow", "", " morrow", "../x", "morrow\n"}) {
            ObjectNode in = defaults();
            in.put("themeId", bad);
            assertThatThrownBy(() -> run(in, currentMorrow())).as("[%s]", bad).isInstanceOfSatisfying(ThemeValidationException.class, e -> {
                assertThat(e.getErrors()).hasSize(1);
                assertThat(e.getErrors().get(0).path()).isEqualTo("themeId");
                assertThat(e.getErrors().get(0).code()).isEqualTo("NOT_ALLOWED_VALUE");
            });
        }
        for (JsonNode bad : new JsonNode[]{MAPPER.getNodeFactory().numberNode(5), MAPPER.createObjectNode(), MAPPER.createArrayNode(),
                MAPPER.getNodeFactory().booleanNode(true)}) {
            ObjectNode in = defaults();
            in.set("themeId", bad);
            assertThatThrownBy(() -> run(in, currentMorrow())).isInstanceOfSatisfying(ThemeValidationException.class,
                    e -> assertThat(e.getErrors().get(0).code()).isEqualTo("WRONG_TYPE"));
        }
    }

    // ------------------------------------------------------------------ assets and uploads

    @Test
    void logoKeptBackgroundNulled_logoMissingBecomesNull() {
        ObjectNode in = defaults();
        in.with("assets").putObject("logo").put("alt", "x").put("url", "https://res.cloudinary.com/test/image/upload/shopfi/a/branding/logo.png");
        in.with("assets").putObject("background").put("url", "https://res.cloudinary.com/test/image/upload/shopfi/a/branding/background.png");
        ObjectNode out = run(in, currentMorrow());
        assertThat(out.at("/assets/logo/alt").asText()).isEqualTo("x");
        assertThat(out.at("/assets/background").isNull()).isTrue();
        in.remove("assets");
        out = run(in, currentMorrow());
        assertThat(out.at("/assets/logo").isNull()).isTrue();
        assertThat(out.at("/assets/background").isNull()).isTrue();
    }

    @Test
    void unknownTopLevelMembersSurviveSoTheSchemaStillRejectsThem() {
        ObjectNode in = defaults();
        in.put("surprise", 1);
        assertThat(run(in, currentMorrow()).has("surprise")).isTrue();
    }

    @Test
    void uploadsAreAdjusted_backgroundIgnored_heroImagePartFollowsTheHero() {
        BrandingFile f = new BrandingFile(new byte[]{1}, "image/png", "a.png");
        ThemeUploads up = new ThemeUploads(f, f, Map.of("main", f));
        ThemeUploads out = canon.adjust(up, "main");
        assertThat(out.background()).isNull();
        assertThat(out.logo()).isSameAs(f);
        assertThat(out.sectionImages()).containsOnlyKeys("hero");
        // an unrelated key is left alone (the service reports UNKNOWN_REFERENCE for it)
        assertThat(canon.adjust(new ThemeUploads(null, null, Map.of("other", f)), "main").sectionImages()).containsOnlyKeys("other");
        assertThat(canon.adjust(new ThemeUploads(null, null, Map.of("hero", f)), "hero").sectionImages()).containsOnlyKeys("hero");
    }

    // ------------------------------------------------------------------ idempotence

    @Test
    void canonicalizingTwiceChangesNothing() {
        ObjectNode in = acme();
        in.put("themeId", "blush");
        ObjectNode once = run(in, currentMorrow());
        ObjectNode twice = run(once.deepCopy(), once);
        assertThat(twice).isEqualTo(once);
        ObjectNode thrice = run(twice.deepCopy(), twice);
        assertThat(thrice).isEqualTo(once);
    }

    // ------------------------------------------------------------------ GET resolver

    @Test
    void untouchedDefaultResolvesToMorrowWithMorrowTokens() {
        ObjectNode r = canon.resolveStored(defaults(), defaults());
        assertThat(r.get("themeId").asText()).isEqualTo("morrow");
        assertThat(r.get("tokens")).isEqualTo(catalog.tokens("morrow"));
        // pages are not touched on read
        assertThat(r.get("pages")).isEqualTo(defaults().get("pages"));
    }

    @Test
    void untouchedDefaultIgnoresServerStampedFields() {
        ObjectNode stored = defaults();
        stored.put("revision", 0).put("updatedAt", "2030-01-01T00:00:00Z");
        assertThat(canon.resolveStored(stored, defaults()).get("themeId").asText()).isEqualTo("morrow");
    }

    @Test
    void anyDifferenceFromTheDefaultMakesItLegacyWithItsOwnTokens() {
        ObjectNode stored = defaults();
        ((ObjectNode) stored.at("/tokens/colors")).put("primary", "#222222");
        ObjectNode r = canon.resolveStored(stored, defaults());
        assertThat(r.get("themeId").asText()).isEqualTo("custom-legacy");
        assertThat(r.get("tokens")).isEqualTo(stored.get("tokens"));

        ObjectNode heroEdited = defaults();
        ((ObjectNode) section(heroEdited, "hero").get("props")).put("heading", "Hi");
        ObjectNode r2 = canon.resolveStored(heroEdited, defaults());
        assertThat(r2.get("themeId").asText()).isEqualTo("custom-legacy");
        assertThat(r2.get("tokens")).isEqualTo(defaults().get("tokens")); // keeps today's colours
    }

    @Test
    void tokensEqualToACatalogThemeResolveToThatTheme() {
        ObjectNode stored = defaults();
        stored.set("tokens", catalog.tokens("ocean"));
        ((ObjectNode) section(stored, "hero").get("props")).put("heading", "Edited");
        ObjectNode r = canon.resolveStored(stored, defaults());
        assertThat(r.get("themeId").asText()).isEqualTo("ocean");
        assertThat(r.get("tokens")).isEqualTo(catalog.tokens("ocean"));
    }

    @Test
    void storedThemeIdWinsAndIsNeverRewrittenOnRead() {
        ObjectNode stored = defaults();
        stored.put("themeId", "slate");
        stored.set("tokens", catalog.tokens("ocean")); // catalog edited since: stored data is not re-skinned
        ObjectNode r = canon.resolveStored(stored, defaults());
        assertThat(r).isEqualTo(stored);
    }

    @Test
    void resolveDoesNotMutateItsInput_andToleratesAMissingDefault() {
        ObjectNode stored = acme();
        ObjectNode copy = stored.deepCopy();
        canon.resolveStored(stored, defaults());
        assertThat(stored).isEqualTo(copy);
        assertThat(canon.resolveStored(defaults(), null).get("themeId").asText()).isEqualTo("custom-legacy");
    }

    // ------------------------------------------------------------------ owner decisions 2026-11-24: announcement, story, footer links, social

    @Test
    void announcementIsKeptWithFixedIdAndTone_butOnlyTheFirstBanner() {
        ObjectNode in = acme();
        ObjectNode out = run(in, currentMorrow());
        JsonNode b = out.at("/pages/home/1");
        JsonNode promo = section(in, "promo");
        assertThat(b.get("id").asText()).isEqualTo("announcement");
        assertThat(b.get("type").asText()).isEqualTo("banner");
        assertThat(b.at("/props/message")).isEqualTo(promo.at("/props/message"));
        assertThat(b.at("/props/linkLabel")).isEqualTo(promo.at("/props/linkLabel"));
        assertThat(b.at("/props/linkHref")).isEqualTo(promo.at("/props/linkHref"));
        assertThat(b.at("/props/dismissible").asBoolean()).isTrue();
        assertThat(b.at("/props/tone").asText()).as("tone is fixed").isEqualTo("primary");
        assertThat(b.get("visible").asBoolean()).isTrue();
        assertThat(out.toString()).doesNotContain("old-sale");
    }

    @Test
    void announcementEnabledFlagIsKept_defaultsToTrue() {
        ObjectNode in = defaults();
        ArrayNode home = (ArrayNode) in.at("/pages/home");
        home.removeAll();
        home.add(json("{\"id\":\"x\",\"type\":\"banner\",\"props\":{\"message\":\"Hi\"},\"visible\":false,\"order\":3}"));
        assertThat(run(in, currentMorrow()).at("/pages/home/0/visible").asBoolean()).isFalse();
        ((ObjectNode) home.get(0)).remove("visible");
        assertThat(run(in, currentMorrow()).at("/pages/home/0/visible").asBoolean()).isTrue();
    }

    @Test
    void storyIsKept_richTextIsDropped() {
        ObjectNode in = defaults();
        ArrayNode home = (ArrayNode) in.at("/pages/home");
        home.removeAll();
        home.add(json("{\"id\":\"rt\",\"type\":\"rich-text\",\"props\":{\"body\":\"**x**\"},\"visible\":true,\"order\":1}"));
        home.add(json("{\"id\":\"whatever\",\"type\":\"story\",\"props\":{\"heading\":\"Our story\",\"body\":\"Line one\\nLine two\",\"extra\":1},\"visible\":false,\"order\":9}"));
        home.add(json("{\"id\":\"story2\",\"type\":\"story\",\"props\":{\"heading\":\"Second\",\"body\":\"b\"},\"visible\":true,\"order\":10}"));
        ObjectNode out = run(in, currentMorrow());
        assertThat(out.at("/pages/home")).hasSize(1);
        JsonNode st = out.at("/pages/home/0");
        assertThat(st.get("id").asText()).isEqualTo("story");
        assertThat(st.get("type").asText()).isEqualTo("story");
        assertThat(st.at("/props/heading").asText()).isEqualTo("Our story");
        assertThat(st.at("/props/body").asText()).isEqualTo("Line one\nLine two");
        assertThat(st.at("/props").has("extra")).isFalse();
        assertThat(st.get("visible").asBoolean()).isFalse();
        assertThat(out.toString()).doesNotContain("rich-text").doesNotContain("Second");
    }

    @Test
    void footerKeepsOnlyLinkGroups_noGroupsMeansNoFooter() {
        ObjectNode out = run(acme(), currentMorrow());
        JsonNode f = out.at("/pages/footer/0");
        assertThat(f.get("id").asText()).isEqualTo("footer");
        assertThat(f.at("/props").fieldNames()).toIterable().containsExactly("linkGroups");
        assertThat(f.at("/props/linkGroups")).isEqualTo(section(acme(), "footer").at("/props/linkGroups"));
        assertThat(out.toString()).doesNotContain("Fragrance, simply.").doesNotContain("Acme Perfumes Ltd").doesNotContain("instagram.com/acmeperfumes");
        ObjectNode none = acme();
        ((ObjectNode) section(none, "footer").get("props")).putArray("linkGroups");
        assertThat(run(none, currentMorrow()).at("/pages/footer")).isEmpty();
        ObjectNode noProps = defaults();
        assertThat(run(noProps, currentMorrow()).at("/pages/footer")).isEmpty();
    }

    @Test
    void socialIsReplacedWhenSent_nullsDropped_andKeptWhenTheClientDoesNotKnowIt() {
        ObjectNode current = currentMorrow();
        ((ObjectNode) current).putObject("social").put("instagram", "https://instagram.com/keep");
        ObjectNode absent = defaults();
        assertThat(run(absent, current).at("/social").toString()).isEqualTo("{\"instagram\":\"https://instagram.com/keep\"}");
        ObjectNode sent = defaults();
        sent.putObject("social").put("facebook", "https://facebook.com/x").putNull("instagram");
        assertThat(run(sent, current).at("/social").toString()).isEqualTo("{\"facebook\":\"https://facebook.com/x\"}");
        ObjectNode empty = defaults();
        empty.putObject("social");
        assertThat(run(empty, current).at("/social").size()).isZero();
        assertThat(run(defaults(), currentMorrow()).at("/social").isObject()).isTrue();
        ObjectNode wrong = defaults();
        wrong.put("social", "nope");
        assertThat(run(wrong, current).at("/social").asText()).isEqualTo("nope"); // left for the schema to reject
    }

    @Test
    void fullBrandingDocumentIsIdempotent() {
        ObjectNode in = acme();
        in.put("themeId", "sand");
        in.putObject("social").put("youtube", "https://youtube.com/@acme");
        ((ArrayNode) in.at("/pages/home")).add(json("{\"id\":\"s\",\"type\":\"story\",\"props\":{\"heading\":\"H\",\"body\":\"B\"},\"visible\":true,\"order\":20}"));
        ObjectNode once = run(in, currentMorrow());
        assertThat(once.at("/pages/home")).hasSize(3);
        assertThat(run(once.deepCopy(), once)).isEqualTo(once);
    }
}
