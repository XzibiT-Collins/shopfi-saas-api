package com.example.perfume_budget.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BILL-01 (billing.md section 21 item 13): loads the tagged examples of {@code docs/api/billing.md}
 * ({@code <!-- example: NAME valid|invalid|response Schema [status] -->} and {@code <!-- fragment: NAME Schema -->}, each
 * followed by a json fence) and validates against {@code docs/api/billing.schema.json}. Every tag must be parsed: a typo can
 * never silently drop an example. It reads ONLY billing.md and billing.schema.json (never the plans.md tables).
 */
public final class BillingDocExamples {

    public static final Path DOCS = Path.of("docs", "api");
    public static final ObjectMapper MAPPER = new ObjectMapper();

    public record Example(String name, String kind, String schema, Integer status, JsonNode json) {
    }

    public record Fragment(String name, String schema, JsonNode json) {
    }

    public final Map<String, Example> examples = new LinkedHashMap<>();
    public final Map<String, Fragment> fragments = new LinkedHashMap<>();
    public final JsonNode schemaDoc;

    public BillingDocExamples() {
        try {
            schemaDoc = MAPPER.readTree(Files.readString(DOCS.resolve("billing.schema.json")));
            String doc = Files.readString(DOCS.resolve("billing.md"));
            Matcher m = Pattern.compile(
                    "(?m)^<!-- example: (\\S+) (valid|invalid|response) (\\S+)(?: (\\d{3}))? -->\\s*```json\\s*(.*?)```",
                    Pattern.DOTALL).matcher(doc);
            while (m.find()) {
                Example e = new Example(m.group(1), m.group(2), m.group(3),
                        m.group(4) == null ? null : Integer.valueOf(m.group(4)), MAPPER.readTree(m.group(5)));
                if (examples.put(e.name(), e) != null) {
                    throw new IllegalStateException("duplicate example name " + e.name());
                }
                boolean hasStatus = e.status() != null;
                if (hasStatus != !e.kind().equals("valid")) {
                    throw new IllegalStateException("status must be present exactly for invalid / response examples: " + e.name());
                }
            }
            long tags = Pattern.compile("(?m)^<!-- example:").matcher(doc).results().count();
            if (tags != examples.size()) {
                throw new IllegalStateException(tags + " example tags in billing.md but " + examples.size() + " parsed");
            }
            Matcher f = Pattern.compile("(?m)^<!-- fragment: (\\S+) (\\S+) -->\\s*```json\\s*(.*?)```", Pattern.DOTALL).matcher(doc);
            while (f.find()) {
                if (fragments.put(f.group(1), new Fragment(f.group(1), f.group(2), MAPPER.readTree(f.group(3)))) != null) {
                    throw new IllegalStateException("duplicate fragment name " + f.group(1));
                }
            }
            long fragmentTags = Pattern.compile("(?m)^<!-- fragment:").matcher(doc).results().count();
            if (fragmentTags != fragments.size()) {
                throw new IllegalStateException(fragmentTags + " fragment tags but " + fragments.size() + " parsed");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Set<ValidationMessage> validate(String def, JsonNode instance) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("$schema", schemaDoc.get("$schema").asText());
        wrapper.put("$ref", "#/$defs/" + def);
        wrapper.set("$defs", schemaDoc.get("$defs"));
        JsonSchema s = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(wrapper);
        return s.validate(instance);
    }

    public Example get(String name) {
        Example e = examples.get(name);
        if (e == null) {
            throw new AssertionError("no example " + name);
        }
        return e;
    }
}
