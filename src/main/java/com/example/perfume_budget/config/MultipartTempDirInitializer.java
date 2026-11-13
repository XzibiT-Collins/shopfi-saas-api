package com.example.perfume_budget.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Prepares the dedicated multipart spool directory ({@code spring.servlet.multipart.location}, THEME-04c):
 * creates it (owner-only on POSIX) and removes any files left behind by a previous process (a crash or kill leaves
 * partially spooled parts; Tomcat only deletes parts of requests that finish in this JVM). The directory is
 * dedicated to multipart spooling, so emptying it at startup is safe. Failure to prepare it is logged and does not
 * stop the application (Tomcat then reports the problem per request).
 */
@Slf4j
@Component
public class MultipartTempDirInitializer implements InitializingBean {

    private final String location;

    public MultipartTempDirInitializer(@Value("${spring.servlet.multipart.location:}") String location) {
        this.location = location;
    }

    @Override
    public void afterPropertiesSet() {
        if (location == null || location.isBlank()) {
            return;
        }
        Path dir = Path.of(location).toAbsolutePath().normalize();
        try {
            if (Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.filter(p -> !p.equals(dir)).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Could not remove stale multipart temp entry {}", p.getFileName());
                        }
                    });
                }
            } else {
                Files.createDirectories(dir);
                try {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ignored) {
                    // non-POSIX file system
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not prepare multipart temp dir {}: {}", dir, e.getClass().getSimpleName());
        }
    }
}
