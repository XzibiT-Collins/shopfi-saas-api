package com.example.perfume_budget.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Points Tomcat's multipart spooling at this JVM's own subdirectory (see {@link MultipartTempDirInitializer}). Replaces
 * Boot's auto-configured {@code MultipartConfigElement}, which would otherwise use the shared base directly. All other
 * multipart settings (sizes, threshold) still come from {@code spring.servlet.multipart.*}.
 */
@Configuration
@EnableConfigurationProperties(MultipartProperties.class)
public class MultipartSpoolConfig {

    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties props, MultipartTempDirInitializer spool) {
        if (spool.instanceDir() != null) {
            props.setLocation(spool.instanceDir().toString());
        }
        return props.createMultipartConfig();
    }
}
