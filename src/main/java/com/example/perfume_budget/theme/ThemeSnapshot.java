package com.example.perfume_budget.theme;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Serializable;

/**
 * Immutable, cache-safe view of a tenant's theme: the document as a JSON string (so it survives JDK
 * serialisation into Redis and cannot be mutated by callers) plus the entity version. The ETag is
 * {@code W/"<version>"}; the document's {@code revision} is {@code version - 1}.
 */
public record ThemeSnapshot(String themeJson, long version) implements Serializable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String etag() {
        return "W/\"" + version + "\"";
    }

    /** A fresh, independent copy of the document. */
    public JsonNode theme() {
        try {
            return MAPPER.readTree(themeJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt cached theme", e);
        }
    }

    /**
     * Parses an {@code If-Match} value ({@code W/"3"} or {@code "3"}) to the expected version.
     *
     * @throws IllegalArgumentException when malformed (callers map to 400/412)
     */
    public static long parseEtag(String ifMatch) {
        if (ifMatch == null) {
            throw new IllegalArgumentException("Missing ETag");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() < 3 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') {
            throw new IllegalArgumentException("Malformed ETag");
        }
        try {
            long n = Long.parseLong(v.substring(1, v.length() - 1));
            if (n < 1) {
                throw new IllegalArgumentException("Malformed ETag");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed ETag");
        }
    }
}
