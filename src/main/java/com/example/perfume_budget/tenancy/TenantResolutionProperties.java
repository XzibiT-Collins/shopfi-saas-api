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

    private Oauth2 oauth2 = new Oauth2();

    public boolean isPlatformDomain(String host) {
        return host != null && platformDomains.contains(host);
    }

    @Getter
    @Setter
    public static class HeaderResolution {
        private boolean enabled = false;
    }

    /**
     * OAuth2 tenant resolution: the login's {@code redirect_uri} host identifies the tenant
     * (matched against the registry). In dev there are no subdomains/custom domains, so the
     * tenant comes from the {@code X-Tenant-ID} header and the redirect host is accepted only
     * if it is one of these allowed dev hosts (open-redirect guard).
     */
    @Getter
    @Setter
    public static class Oauth2 {
        private List<String> devAllowedRedirectHosts = new ArrayList<>(List.of("localhost", "127.0.0.1"));
    }
}
