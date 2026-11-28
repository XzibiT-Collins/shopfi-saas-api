package com.example.perfume_budget.theme;

import com.example.perfume_budget.entitlement.EntitlementService;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.example.perfume_budget.theme.ThemeTestDocs.MAPPER;
import static com.example.perfume_budget.theme.ThemeTestDocs.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PLAN-02 THEME_ADVANCED allowlist (plans.md section 2): fails closed, compares canonical forms, fires only for an added
 * or changed advanced element.
 */
class ThemePlanGateTest {

    private final ThemeCatalog catalog = new ThemeCatalog(MAPPER);
    private final ThemeCanonicalizer canonicalizer = new ThemeCanonicalizer(catalog, MAPPER);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final ThemePlanGate gate = new ThemePlanGate(catalog, canonicalizer, entitlements);

    /** The stored document as the GET path resolves it. */
    private ObjectNode stored(ObjectNode doc) {
        return canonicalizer.resolveStored(doc, ThemeTestDocs.defaults());
    }

    /** What a PUT of {@code submitted} over {@code stored} would store. */
    private ObjectNode canonical(ObjectNode submitted, ObjectNode storedResolved) {
        return canonicalizer.canonicalize(submitted, storedResolved).document();
    }

    private static ObjectNode basic() {
        ObjectNode d = ThemeTestDocs.defaults();
        ArrayNode home = MAPPER.createArrayNode();
        d.get("pages").get("home").forEach(s -> {
            if (s.path("type").asText().equals("hero")) {
                home.add(s);
            }
        });
        ((ObjectNode) d.get("pages")).set("home", home);
        ((ObjectNode) d.get("pages")).set("footer", MAPPER.createArrayNode());
        return d;
    }

    private static ObjectNode with(ObjectNode doc, String sectionJson) {
        ObjectNode c = doc.deepCopy();
        ((ArrayNode) c.get("pages").get("home")).add(json(sectionJson));
        return c;
    }

    private static final String BANNER = "{\"id\":\"announcement\",\"type\":\"banner\",\"props\":{\"message\":\"Hi\"},\"visible\":true,\"order\":1}";
    private static final String STORY = "{\"id\":\"story\",\"type\":\"story\",\"props\":{\"heading\":\"Us\",\"body\":\"Text\"},\"visible\":true,\"order\":2}";

    @Test
    void basicContentOnly_neverFires_evenWhenEdited() {
        ObjectNode s = stored(basic());
        ObjectNode edited = basic();
        ((ObjectNode) edited.get("pages").get("home").get(0).get("props")).put("heading", "New heading");
        assertThat(gate.addsAdvancedContent(canonical(edited, s), s)).isFalse();
        // switching to another catalog theme is basic too
        edited.put("themeId", "ocean");
        assertThat(gate.addsAdvancedContent(canonical(edited, s), s)).isFalse();
    }

    @Test
    void addingOrChangingABannerOrStoryFires_unchangedStoredAdvancedContentDoesNot() {
        ObjectNode s0 = stored(basic());
        assertThat(gate.addsAdvancedContent(canonical(with(basic(), BANNER), s0), s0)).as("add banner").isTrue();
        assertThat(gate.addsAdvancedContent(canonical(with(basic(), STORY), s0), s0)).as("add story").isTrue();

        ObjectNode withBoth = stored(canonical(with(with(basic(), BANNER), STORY), s0));
        assertThat(gate.addsAdvancedContent(canonical(withBoth.deepCopy(), withBoth), withBoth)).as("unchanged").isFalse();
        ObjectNode changed = withBoth.deepCopy();
        ((ObjectNode) changed.get("pages").get("home").get(1).get("props")).put("message", "Other text");
        assertThat(gate.addsAdvancedContent(canonical(changed, withBoth), withBoth)).as("changed banner").isTrue();
        // removing or hiding advanced content is never gated
        assertThat(gate.addsAdvancedContent(canonical(basic(), withBoth), withBoth)).as("removed").isFalse();
        ObjectNode hidden = withBoth.deepCopy();
        ((ObjectNode) hidden.get("pages").get("home").get(1)).put("visible", false);
        assertThat(gate.addsAdvancedContent(canonical(hidden, withBoth), withBoth)).as("hidden").isFalse();
    }

    @Test
    void footerLinkGroupsAreAdvanced() {
        ObjectNode s = stored(basic());
        ObjectNode f = basic();
        ((ArrayNode) f.get("pages").get("footer")).add(json("{\"id\":\"footer\",\"type\":\"footer\",\"props\":{\"linkGroups\":"
                + "[{\"title\":\"Help\",\"links\":[{\"label\":\"FAQ\",\"href\":\"/faq\"}]}]},\"visible\":true,\"order\":0}"));
        assertThat(gate.addsAdvancedContent(canonical(f, s), s)).isTrue();
    }

    @Test
    void failsClosed_unknownSectionTypes_customTokens_andABackgroundAreAdvanced() {
        ObjectNode base = (ObjectNode) canonical(basic(), stored(basic()));
        assertThat(gate.advanced(base)).isEmpty();
        ObjectNode later = base.deepCopy();
        ((ArrayNode) later.get("pages").get("home")).add(json("{\"id\":\"t\",\"type\":\"testimonials\",\"props\":{},\"visible\":true,\"order\":1}"));
        assertThat(gate.advanced(later)).as("a section type added later is gated until allowlisted").hasSize(1);
        ObjectNode tokens = base.deepCopy();
        ((ObjectNode) tokens.get("tokens").get("colors")).put("primary", "#123456");
        assertThat(gate.advanced(tokens)).as("tokens that are not a catalog theme's").hasSize(1);
        ObjectNode bg = base.deepCopy();
        ((ObjectNode) bg.get("assets")).put("background", "https://res.cloudinary.com/x/bg.png");
        assertThat(gate.advanced(bg)).hasSize(1);
        assertThat(ThemePlanGate.BASIC_SECTION_TYPES).containsExactlyInAnyOrder("hero", "featured-products", "category-grid");
    }

    @Test
    void aLegacyCustomThemeKeptAsItIsNeverFires() {
        ObjectNode legacy = basic();
        legacy.remove("themeId");
        ((ObjectNode) legacy.get("tokens").get("colors")).put("primary", "#123456");
        ObjectNode s = stored(legacy);
        assertThat(s.get("themeId").asText()).isEqualTo(ThemeCatalog.LEGACY_ID);
        ObjectNode resend = s.deepCopy();
        resend.remove("themeId"); // the editor keeps the stored theme
        assertThat(gate.addsAdvancedContent(canonical(resend, s), s)).isFalse();
    }

    @Test
    void checkCallsTheEntitlementOnlyWhenItFires() {
        ObjectNode s = stored(basic());
        gate.check(canonical(basic(), s), s);
        verify(entitlements, never()).requireWrite(PlanFeature.THEME_ADVANCED);
        gate.check(canonical(with(basic(), BANNER), s), s);
        verify(entitlements).requireWrite(PlanFeature.THEME_ADVANCED);
    }
}
