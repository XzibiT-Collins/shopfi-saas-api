package com.example.perfume_budget.theme;

import com.example.perfume_budget.entitlement.EntitlementService;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * PLAN-02 {@code THEME_ADVANCED} request-content gate of {@code PUT /api/v1/theme} (docs/api/plans.md section 2), an
 * ALLOWLIST that fails closed. Without the feature a theme may use only: a catalog theme ({@code themeId}) with its own
 * tokens, logo and branding (social links), and visible sections of the types {@link #BASIC_SECTION_TYPES} plus a
 * basic footer without {@code linkGroups}. Every other element is "advanced": any other section type (today
 * {@code rich-text}, {@code story}, {@code banner}; any type added later until it is put on the allowlist), footer
 * {@code linkGroups}, tokens that are not exactly a catalog theme's, a background image.
 *
 * <p>Both documents are compared on the canonical form ({@link ThemeCanonicalizer}). The gate fires only when the new
 * document has an advanced element the stored one does not have (added or changed): unchanged stored content, removing
 * or hiding advanced content, and basic edits are never a 402. A fired gate is a gated write
 * ({@link EntitlementService#requireWrite}: NOT_IN_PLAN, then SUBSCRIPTION_INACTIVE).
 */
@Component
public class ThemePlanGate {

    /** The section types every plan may use. Add a new basic type here deliberately; anything unknown is advanced. */
    public static final Set<String> BASIC_SECTION_TYPES = Set.of("hero", "featured-products", "category-grid");

    private final ThemeCatalog catalog;
    private final ThemeCanonicalizer canonicalizer;
    private final EntitlementService entitlementService;

    public ThemePlanGate(ThemeCatalog catalog, ThemeCanonicalizer canonicalizer, EntitlementService entitlementService) {
        this.catalog = catalog;
        this.canonicalizer = canonicalizer;
        this.entitlementService = entitlementService;
    }

    /**
     * @param submittedCanonical the canonical form of the request ({@link ThemeCanonicalizer#canonicalize})
     * @param storedResolved     the stored document as {@link ThemeCanonicalizer#resolveStored} returns it
     */
    public void check(ObjectNode submittedCanonical, JsonNode storedResolved) {
        if (addsAdvancedContent(submittedCanonical, storedResolved)) {
            entitlementService.requireWrite(PlanFeature.THEME_ADVANCED);
        }
    }

    boolean addsAdvancedContent(ObjectNode submittedCanonical, JsonNode storedResolved) {
        Set<String> before = advanced(storedCanonical(storedResolved));
        Set<String> after = advanced(submittedCanonical);
        after.removeAll(before);
        return !after.isEmpty();
    }

    /** The stored document through the same canonical form (its own themeId and tokens are kept as they are). */
    private ObjectNode storedCanonical(JsonNode storedResolved) {
        if (storedResolved == null || !storedResolved.isObject()) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); // nothing stored: all new
        }
        ObjectNode copy = ((ObjectNode) storedResolved).deepCopy();
        copy.remove("themeId"); // keep the stored theme and tokens (a legacy id is not selectable by a client)
        return canonicalizer.canonicalize(copy, storedResolved).document();
    }

    /** Every advanced element of a canonical document, as a comparable string (content without the position). */
    Set<String> advanced(JsonNode doc) {
        Set<String> out = new HashSet<>();
        String themeId = doc.path("themeId").asText("");
        JsonNode tokens = doc.path("tokens");
        if (!catalog.isSelectable(themeId) || !catalog.tokens(themeId).equals(tokens)) {
            out.add("tokens:" + tokens);
        }
        JsonNode background = doc.path("assets").path("background");
        if (!background.isMissingNode() && !background.isNull()) {
            out.add("background:" + background);
        }
        Iterator<Map.Entry<String, JsonNode>> pages = doc.path("pages").fields();
        while (pages.hasNext()) {
            Map.Entry<String, JsonNode> page = pages.next();
            if (!page.getValue().isArray()) {
                continue;
            }
            for (JsonNode section : page.getValue()) {
                if (!section.isObject() || !section.path("visible").asBoolean(true)) {
                    continue; // a hidden section is not used
                }
                String type = section.path("type").asText("");
                ObjectNode content = ((ObjectNode) section).deepCopy();
                content.remove("order");
                if ("footer".equals(type)) {
                    JsonNode groups = section.path("props").path("linkGroups");
                    if (groups.isArray() && !groups.isEmpty()) {
                        out.add("footer.linkGroups:" + groups);
                    }
                } else if (!BASIC_SECTION_TYPES.contains(type)) {
                    out.add("section:" + page.getKey() + ":" + content);
                }
            }
        }
        return out;
    }
}
