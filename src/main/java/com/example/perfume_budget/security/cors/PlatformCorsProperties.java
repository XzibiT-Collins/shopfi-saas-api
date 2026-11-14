package com.example.perfume_budget.security.cors;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS for the platform control plane ({@code /api/v1/platform/**}, PLAT-03 AC3). Prefix {@code platform.cors}.
 * Empty by default: no browser origin may call the platform API until the console origin(s) are configured.
 * Tenant origins and {@code cors.allowed-origins} never apply to these paths.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "platform.cors")
public class PlatformCorsProperties {

    /** Exact origins of the platform-admin console, e.g. {@code https://console.shopfi.app}. */
    private List<String> allowedOrigins = new ArrayList<>();
}
