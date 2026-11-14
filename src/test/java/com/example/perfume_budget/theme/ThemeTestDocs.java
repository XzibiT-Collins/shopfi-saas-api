package com.example.perfume_budget.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test fixtures: the valid example documents embedded in docs/api/theme-contract.md. */
final class ThemeTestDocs {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Path DOCS = Path.of("docs", "api");

    private ThemeTestDocs() {
    }

    static ObjectNode example(String name) {
        try {
            Pattern p = Pattern.compile("<!-- example: " + Pattern.quote(name) + " valid -->\\s*```json\\s*(.*?)```", Pattern.DOTALL);
            Matcher m = p.matcher(Files.readString(DOCS.resolve("theme-contract.md")));
            if (!m.find()) {
                throw new IllegalStateException("no example " + name);
            }
            return (ObjectNode) MAPPER.readTree(m.group(1));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ObjectNode acme() {
        return example("acme-full");
    }

    static ObjectNode defaults() {
        return example("default-new-tenant");
    }

    static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Section by id in any slot. */
    static ObjectNode section(ObjectNode theme, String id) {
        for (JsonNode slot : theme.get("pages")) {
            for (JsonNode s : slot) {
                if (id.equals(s.get("id").asText())) {
                    return (ObjectNode) s;
                }
            }
        }
        throw new IllegalStateException("no section " + id);
    }
}
