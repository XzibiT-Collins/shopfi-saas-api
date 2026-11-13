package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartTempDirInitializerTest {

    @TempDir Path base;

    @Test
    void createsMissingDirectory() {
        Path dir = base.resolve("spool");
        new MultipartTempDirInitializer(dir.toString()).afterPropertiesSet();
        assertThat(dir).isDirectory();
    }

    @Test
    void emptiesLeftoversFromAPreviousProcess() throws Exception {
        Path dir = Files.createDirectory(base.resolve("spool"));
        Files.writeString(dir.resolve("upload_123.tmp"), "orphan");
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/x.tmp"), "orphan");
        new MultipartTempDirInitializer(dir.toString()).afterPropertiesSet();
        assertThat(dir).isDirectory();
        try (var s = Files.list(dir)) {
            assertThat(s).isEmpty();
        }
    }
}
