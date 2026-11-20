package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THEME-07 AC3: the container image must not spool multipart parts into the shared, predictable /tmp: it sets
 * MULTIPART_TMP_DIR to a directory the service user owns, and the contract documents that as a deployment requirement.
 */
class MultipartSpoolDeploymentTest {

    @Test
    void dockerfileSetsAServiceOwnedSpoolDirectoryOutsideTmp() throws Exception {
        String docker = Files.readString(Path.of("Dockerfile"));
        assertThat(docker).contains("ENV MULTIPART_TMP_DIR=/app/shopfi-multipart");
        assertThat(docker).contains("chown appuser:appgroup /app/shopfi-multipart");
        assertThat(docker.indexOf("MULTIPART_TMP_DIR")).as("set before the service user takes over (USER appuser)")
                .isLessThan(docker.indexOf("USER appuser"));
        // the directory name is the one MultipartTempDirInitializer recognises as ours
        assertThat(Path.of("/app/shopfi-multipart").getFileName().toString()).isEqualTo(MultipartTempDirInitializer.DIR_NAME);
    }

    @Test
    void contractAndPropertiesDocumentTheDeploymentRequirement() throws Exception {
        String contract = Files.readString(Path.of("docs", "api", "theme-contract.md"));
        assertThat(contract).contains("Deployment requirement (THEME-07 AC3)").contains("MULTIPART_TMP_DIR");
        String props = Files.readString(Path.of("src", "main", "resources", "application.properties"));
        assertThat(props).contains("DEPLOYMENT REQUIREMENT (THEME-07 AC3)");
    }
}
