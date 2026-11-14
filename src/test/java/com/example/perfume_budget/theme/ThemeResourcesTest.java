package com.example.perfume_budget.theme;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the classpath copies (schema, default document) identical to their sources of truth. */
class ThemeResourcesTest {

    @Test
    void classpathSchemaIsByteIdenticalToDocsCopy() throws IOException {
        byte[] docs = Files.readAllBytes(Path.of("docs", "api", "theme.schema.json"));
        byte[] resource = Files.readAllBytes(Path.of("src", "main", "resources", "theme", "theme.schema.json"));
        assertThat(resource).isEqualTo(docs);
        try (var in = ThemeValidator.class.getClassLoader().getResourceAsStream("theme/theme.schema.json")) {
            assertThat(in.readAllBytes()).isEqualTo(docs);
        }
    }

    @Test
    void defaultDocumentResourceEqualsMigrationDefaultAndDocExample() throws IOException {
        String sql = Files.readString(Path.of("src", "main", "resources", "db", "migrations", "tenant", "V8__create_store_settings.sql"));
        Matcher m = Pattern.compile("\\$theme\\$(.*?)\\$theme\\$", Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).isTrue();
        var migration = ThemeTestDocs.json(m.group(1));
        try (var in = ThemeValidator.class.getClassLoader().getResourceAsStream("theme/default-theme.json")) {
            var resource = ThemeTestDocs.MAPPER.readTree(in);
            assertThat(resource).isEqualTo(migration);
            assertThat(resource).isEqualTo(ThemeTestDocs.defaults());
        }
    }
}
