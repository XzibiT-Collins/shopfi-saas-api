package com.example.perfume_budget.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** THEME-01: the default theme seeded by Flyway tenant V8 must equal the contract's default example. */
class StoreSettingsMigrationDefaultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode extract(Path file, Pattern p) throws Exception {
        Matcher m = p.matcher(Files.readString(file));
        assertThat(m.find()).as("pattern found in %s", file).isTrue();
        return MAPPER.readTree(m.group(1));
    }

    @Test
    void migrationDefaultThemeEqualsContractDefaultExample() throws Exception {
        JsonNode contract = extract(Path.of("docs/api/theme-contract.md"), Pattern.compile(
                "<!-- example: default-new-tenant valid -->\\s*```json\\s*(.*?)```", Pattern.DOTALL));
        JsonNode migration = extract(
                Path.of("src/main/resources/db/migrations/tenant/V8__create_store_settings.sql"),
                Pattern.compile("\\$theme\\$(.*?)\\$theme\\$", Pattern.DOTALL));
        assertThat(migration).isEqualTo(contract);
        assertThat(migration.get("schemaVersion").asInt()).isEqualTo(1);
    }

    /** SF-BE-01 AC5: V8 is not edited; the resolver maps the stored default row (and so every new tenant) to morrow. */
    @Test
    void migrationDefaultRowResolvesToTheDefaultTheme() throws Exception {
        JsonNode migration = extract(
                Path.of("src/main/resources/db/migrations/tenant/V8__create_store_settings.sql"),
                Pattern.compile("\\$theme\\$(.*?)\\$theme\\$", Pattern.DOTALL));
        assertThat(migration.has("themeId")).isFalse();
        var catalog = new com.example.perfume_budget.theme.ThemeCatalog(MAPPER);
        var resolver = new com.example.perfume_budget.theme.ThemeCanonicalizer(catalog, MAPPER);
        var resolved = resolver.resolveStored((com.fasterxml.jackson.databind.node.ObjectNode) migration.deepCopy(), migration);
        assertThat(resolved.get("themeId").asText()).isEqualTo("morrow");
        assertThat(resolved.get("tokens")).isEqualTo(catalog.tokens("morrow"));
    }
}
