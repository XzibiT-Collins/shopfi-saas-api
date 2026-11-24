package com.example.perfume_budget.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The curated storefront theme catalog (SF-BE-01). Single source of truth is
 * {@code docs/api/frontend/theme-catalog.json}; the classpath copy {@value #RESOURCE} is kept byte-identical by a test.
 * The frontend vendors the same file. A theme is a fixed set of resolved {@code tokens}; the server writes those tokens
 * into the stored document, so a catalog edit never re-skins stored data until the tenant saves again.
 */
@Component
public class ThemeCatalog {

    static final String RESOURCE = "theme/theme-catalog.json";
    /** Theme of an untouched default document and of new tenants. */
    public static final String DEFAULT_ID = "morrow";
    /** Reserved, never selectable: a tenant whose saved custom tokens are kept as they are. */
    public static final String LEGACY_ID = "custom-legacy";
    public static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

    private final Map<String, ObjectNode> tokensById = new LinkedHashMap<>();

    public ThemeCatalog(ObjectMapper mapper) {
        JsonNode root;
        try (InputStream in = ThemeCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            root = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
        for (JsonNode t : root.path("themes")) {
            String id = t.path("id").asText();
            if (!ID.matcher(id).matches() || LEGACY_ID.equals(id) || tokensById.containsKey(id)) {
                throw new IllegalStateException("Invalid or duplicate theme id in catalog: " + id);
            }
            tokensById.put(id, (ObjectNode) t.get("tokens"));
        }
        if (!tokensById.containsKey(DEFAULT_ID)) {
            throw new IllegalStateException("Catalog has no default theme " + DEFAULT_ID);
        }
    }

    /** True for ids a client may submit (never {@link #LEGACY_ID}). */
    public boolean isSelectable(String id) {
        return id != null && tokensById.containsKey(id);
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(tokensById.keySet());
    }

    /** A fresh copy of the resolved tokens of {@code id}. */
    public ObjectNode tokens(String id) {
        ObjectNode t = tokensById.get(id);
        if (t == null) {
            throw new IllegalArgumentException("unknown theme");
        }
        return t.deepCopy();
    }

    /** The catalog theme whose 8 colours and two fonts equal those of {@code tokens}, if any. */
    public Optional<String> matching(JsonNode tokens) {
        if (tokens == null || !tokens.isObject()) {
            return Optional.empty();
        }
        for (Map.Entry<String, ObjectNode> e : tokensById.entrySet()) {
            if (sameLook(e.getValue(), tokens)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    private static boolean sameLook(JsonNode a, JsonNode b) {
        JsonNode ca = a.path("colors");
        JsonNode cb = b.path("colors");
        for (String k : new String[]{"primary", "onPrimary", "accent", "onAccent", "background", "surface", "text", "mutedText"}) {
            if (!ca.path(k).isTextual() || !cb.path(k).isTextual() || !ca.path(k).asText().equalsIgnoreCase(cb.path(k).asText())) {
                return false;
            }
        }
        return a.path("typography").path("headingFont").equals(b.path("typography").path("headingFont"))
                && a.path("typography").path("bodyFont").equals(b.path("typography").path("bodyFont"));
    }
}
