package com.example.perfume_budget.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SF-BE-01: the storefront is a curated theme plus light branding. This class is the only place that decides what a
 * stored document may contain.
 *
 * <ul>
 *   <li>{@link #canonicalize}: the PUT path. Tokens are never taken from the client (they come from the catalog theme
 *       named by {@code themeId}, or from the stored document when {@code themeId} is absent). {@code pages.home} keeps
 *       at most one each of: {@code hero} (id {@code hero}), {@code banner} (the announcement strip, id
 *       {@code announcement}) and {@code story} (the "Our story" block, id {@code story}); {@code pages.footer} keeps a
 *       footer with only {@code linkGroups}; {@code pages.product} is empty; {@code assets.background} is null; top-level
 *       {@code social} keeps one URL per platform. Everything else is dropped, not rejected, so old clients keep working.</li>
 *   <li>{@link #resolveStored}: the GET path. A stored document without {@code themeId} is resolved in memory (never
 *       written): untouched default -> the default theme; tokens equal to a catalog theme -> that theme; anything else
 *       -> the reserved {@link ThemeCatalog#LEGACY_ID}, which keeps the saved tokens.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ThemeCanonicalizer {

    static final String HERO_ID = "hero";
    static final String HERO_CTA_HREF = "/products";
    static final String ANNOUNCEMENT_ID = "announcement";
    static final String STORY_ID = "story";
    static final String FOOTER_ID = "footer";
    private static final String[] BANNER_KEPT = {"message", "linkLabel", "linkHref", "dismissible"};
    private static final String[] HERO_KEPT = {"heading", "subheading", "imageUrl", "imageAlt", "ctaLabel"};

    private final ThemeCatalog catalog;
    private final ObjectMapper mapper;

    /** Result of {@link #canonicalize}: the document and the id the submitted hero had (null if none). */
    public record Canonical(ObjectNode document, String submittedHeroId) {
    }

    /**
     * @param submitted       the normalised client document (not modified)
     * @param currentResolved the tenant's current stored document as returned by {@link #resolveStored}
     * @throws ThemeValidationException {@code themeId} present but unknown or not selectable ({@code NOT_ALLOWED_VALUE})
     *                                  or not a string ({@code WRONG_TYPE}), path {@code themeId}
     */
    public Canonical canonicalize(ObjectNode submitted, JsonNode currentResolved) {
        ObjectNode out = mapper.createObjectNode();
        if (submitted.has("schemaVersion")) {
            out.set("schemaVersion", submitted.get("schemaVersion"));
        }
        // carry unknown top-level members through so the schema still rejects them (UNKNOWN_PROPERTY)
        submitted.fields().forEachRemaining(e -> {
            String k = e.getKey();
            if (!k.equals("tokens") && !k.equals("pages") && !k.equals("assets") && !k.equals("themeId")
                    && !k.equals("schemaVersion") && !k.equals("social")) {
                out.set(k, e.getValue());
            }
        });

        JsonNode requested = submitted.get("themeId");
        if (requested != null && !requested.isNull()) {
            if (!requested.isTextual()) {
                throw new ThemeValidationException(new ThemeFieldError("themeId", "WRONG_TYPE", "themeId must be a string"));
            }
            if (!catalog.isSelectable(requested.asText())) {
                throw new ThemeValidationException(new ThemeFieldError("themeId", "NOT_ALLOWED_VALUE", "unknown theme"));
            }
            out.put("themeId", requested.asText());
            out.set("tokens", catalog.tokens(requested.asText()));
        } else {
            out.put("themeId", currentResolved.path("themeId").asText(ThemeCatalog.LEGACY_ID));
            out.set("tokens", currentResolved.path("tokens").deepCopy());
        }

        ObjectNode assets = out.putObject("assets");
        JsonNode logo = submitted.path("assets").path("logo");
        assets.set("logo", submitted.path("assets").has("logo") ? logo : mapper.nullNode());
        assets.set("background", mapper.nullNode());

        ObjectNode pages = out.putObject("pages");
        ArrayNode home = pages.putArray("home");
        String heroId = null;
        JsonNode hero = null;
        JsonNode banner = null;
        JsonNode story = null;
        JsonNode submittedHome = submitted.path("pages").path("home");
        if (submittedHome.isArray()) {
            for (int i = 0; i < Math.min(submittedHome.size(), 13); i++) {
                JsonNode s = submittedHome.get(i);
                if (!s.isObject()) {
                    continue;
                }
                String type = s.path("type").asText();
                if ("hero".equals(type) && hero == null) {
                    hero = s;
                    heroId = s.path("id").isTextual() ? s.path("id").asText() : null;
                } else if ("banner".equals(type) && banner == null) {
                    banner = s;
                } else if ("story".equals(type) && story == null) {
                    story = s;
                }
            }
        }
        if (hero != null) {
            home.add(hero(hero.path("props")));
        }
        if (banner != null) {
            home.add(banner(banner, home.size()));
        }
        if (story != null) {
            home.add(story(story, home.size()));
        }
        pages.putArray("product");
        ArrayNode footer = pages.putArray("footer");
        JsonNode submittedFooter = submitted.path("pages").path("footer");
        if (submittedFooter.isArray()) {
            for (int i = 0; i < Math.min(submittedFooter.size(), 2); i++) {
                JsonNode f = submittedFooter.get(i);
                JsonNode groups = f.path("props").path("linkGroups");
                if (f.isObject() && "footer".equals(f.path("type").asText()) && groups.isArray() && groups.size() > 0) {
                    ObjectNode sec = footer.addObject();
                    sec.put("id", FOOTER_ID);
                    sec.put("type", "footer");
                    sec.putObject("props").set("linkGroups", groups);
                    sec.put("visible", true);
                    sec.put("order", 0);
                    break;
                }
            }
        }

        // social: replaced when the client sends it (null entries mean "none"); kept as stored when the client does not know it
        JsonNode social = submitted.get("social");
        if (social == null) {
            social = currentResolved.path("social");
        }
        if (social.isObject()) {
            ObjectNode kept = out.putObject("social");
            social.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    kept.set(e.getKey(), e.getValue());
                }
            });
        } else if (social.isNull() || social.isMissingNode()) {
            out.putObject("social");
        } else {
            out.set("social", social); // wrong type: the schema reports it
        }
        return new Canonical(out, heroId);
    }

    private ObjectNode hero(JsonNode props) {
        ObjectNode hero = mapper.createObjectNode();
        hero.put("id", HERO_ID);
        hero.put("type", "hero");
        ObjectNode p = hero.putObject("props");
        for (String k : HERO_KEPT) {
            if (props.has(k)) {
                p.set(k, props.get(k));
            }
        }
        if (p.has("ctaLabel")) {
            p.put("ctaHref", HERO_CTA_HREF);
        }
        p.put("alignment", "left");
        hero.put("visible", true);
        hero.put("order", 0);
        return hero;
    }

    private ObjectNode banner(JsonNode submitted, int order) {
        ObjectNode b = mapper.createObjectNode();
        b.put("id", ANNOUNCEMENT_ID);
        b.put("type", "banner");
        ObjectNode p = b.putObject("props");
        JsonNode props = submitted.path("props");
        for (String k : BANNER_KEPT) {
            if (props.has(k)) {
                p.set(k, props.get(k));
            }
        }
        p.put("tone", "primary");
        b.set("visible", submitted.path("visible").isBoolean() ? submitted.get("visible") : mapper.getNodeFactory().booleanNode(true));
        b.put("order", order);
        return b;
    }

    private ObjectNode story(JsonNode submitted, int order) {
        ObjectNode st = mapper.createObjectNode();
        st.put("id", STORY_ID);
        st.put("type", "story");
        ObjectNode p = st.putObject("props");
        JsonNode props = submitted.path("props");
        for (String k : new String[]{"heading", "body"}) {
            if (props.has(k)) {
                p.set(k, props.get(k));
            }
        }
        st.set("visible", submitted.path("visible").isBoolean() ? submitted.get("visible") : mapper.getNodeFactory().booleanNode(true));
        st.put("order", order);
        return st;
    }

    /** The hero image part may be named after the submitted hero's id; the stored id is always {@code hero}. */
    public ThemeUploads adjust(ThemeUploads files, String submittedHeroId) {
        Map<String, BrandingFile> images = new HashMap<>(files.sectionImages());
        if (submittedHeroId != null && !HERO_ID.equals(submittedHeroId) && images.containsKey(submittedHeroId)
                && !images.containsKey(HERO_ID)) {
            images.put(HERO_ID, images.remove(submittedHeroId));
        }
        return new ThemeUploads(files.logo(), null, images); // the background upload part is ignored (retired)
    }

    /** GET path: adds {@code themeId} (and, for an untouched default document, the default theme's tokens) in memory. */
    public ObjectNode resolveStored(ObjectNode stored, JsonNode defaultDocument) {
        ObjectNode doc = stored.deepCopy();
        JsonNode id = doc.get("themeId");
        if (id != null && id.isTextual() && !id.asText().isEmpty()) {
            return doc;
        }
        if (sameContent(doc, defaultDocument)) {
            doc.put("themeId", ThemeCatalog.DEFAULT_ID);
            doc.set("tokens", catalog.tokens(ThemeCatalog.DEFAULT_ID));
            return doc;
        }
        Optional<String> match = catalog.matching(doc.get("tokens"));
        doc.put("themeId", match.orElse(ThemeCatalog.LEGACY_ID));
        return doc;
    }

    private static boolean sameContent(ObjectNode a, JsonNode b) {
        if (b == null || !b.isObject()) {
            return false;
        }
        ObjectNode x = a.deepCopy();
        ObjectNode y = ((ObjectNode) b).deepCopy();
        for (ObjectNode n : new ObjectNode[]{x, y}) {
            n.remove("revision");
            n.remove("updatedAt");
            n.remove("themeId");
        }
        return x.equals(y);
    }
}
