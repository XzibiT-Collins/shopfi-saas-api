package com.example.perfume_budget.tenancy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for how a request is mapped to a tenant.
 *
 * <ul>
 *   <li>{@code platform-domains} — apex/api hosts that map to the platform (control-plane) scope.</li>
 *   <li>{@code base-domain} — the suffix used for subdomain tenants ({@code <slug>.base-domain}).</li>
 *   <li>{@code header-name} + {@code header-resolution.enabled} — the dev/test tenant header,
 *       which must stay disabled in production (spoofable otherwise).</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tenant")
public class TenantResolutionProperties {

    private List<String> platformDomains = new ArrayList<>(List.of("localhost", "127.0.0.1"));

    private String baseDomain = "";

    private String headerName = "X-Tenant-ID";

    private HeaderResolution headerResolution = new HeaderResolution();

    public boolean isPlatformDomain(String host) {
        return host != null && platformDomains.contains(host);
    }

    @Getter
    @Setter
    public static class HeaderResolution {
        private boolean enabled = false;
    }
}
