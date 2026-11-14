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
 * Loads docs/api/onboarding.md examples and docs/api/onboarding.schema.json for the PROV-03 contract tests.
 * Tag grammar: {@code <!-- example: NAME valid|invalid|response SchemaName [status] -->} followed by a json fence.
 */
final class OnboardingDocExamples {

    static final Path DOCS = Path.of("docs", "api");
    static final ObjectMapper MAPPER = new ObjectMapper();

    record Example(String name, String kind, String schema, Integer status, JsonNode json) {
    }

    final Map<String, Example> examples = new LinkedHashMap<>();
    final JsonNode schemaDoc;

    OnboardingDocExamples() {
        try {
            schemaDoc = MAPPER.readTree(Files.readString(DOCS.resolve("onboarding.schema.json")));
            Pattern p = Pattern.compile(
                    "<!-- example: (\\S+) (valid|invalid|response) (\\S+)(?: (\\d{3}))? -->\\s*```json\\s*(.*?)```",
                    Pattern.DOTALL);
            Matcher m = p.matcher(Files.readString(DOCS.resolve("onboarding.md")));
            while (m.find()) {
                Example e = new Example(m.group(1), m.group(2), m.group(3),
                        m.group(4) == null ? null : Integer.valueOf(m.group(4)), MAPPER.readTree(m.group(5)));
                if (examples.put(e.name(), e) != null) {
                    throw new IllegalStateException("duplicate example name " + e.name());
                }
            }
            // every tag must have been picked up by the regex (guards against a typo silently dropping an example)
            long tags = Pattern.compile("(?m)^<!-- example:").matcher(Files.readString(DOCS.resolve("onboarding.md"))).results().count();
            if (tags != examples.size()) {
                throw new IllegalStateException(tags + " example tags in the doc but " + examples.size() + " parsed");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Validates {@code instance} against {@code #/$defs/<def>} of the published schema. */
    Set<ValidationMessage> validate(String def, JsonNode instance) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("$schema", schemaDoc.get("$schema").asText());
        wrapper.put("$ref", "#/$defs/" + def);
        wrapper.set("$defs", schemaDoc.get("$defs"));
        JsonSchema s = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(wrapper);
        return s.validate(instance);
    }

    Example get(String name) {
        Example e = examples.get(name);
        if (e == null) {
            throw new AssertionError("no example " + name + " in onboarding.md");
        }
        return e;
    }
}
