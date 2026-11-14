package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.OnboardingProperties;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

/**
 * Builds links that point at a tenant's own storefront origin, so a link opened from an email lands on the
 * tenant whose data it refers to (the API is a single host and tenant state, such as password-reset tokens, is
 * tenant scoped).
 *
 * <p>Origin resolution for a tenant slug, first match wins:
 * <ol>
 *   <li>the tenant's custom domain: {@code <scheme>://<custom-domain>};</li>
 *   <li>{@code tenant.base-domain}: {@code <scheme>://<slug>.<base-domain>} (the same host shape the tenant
 *       resolution filter and TenantOnboardingService use; scheme is {@code onboarding.public-scheme});</li>
 *   <li>none: dev profiles with no base domain. {@link #resetPasswordLink} then falls back to
 *       {@code frontend.change-password-url} (the dev origin; an optional {@code {slug}} placeholder is replaced
 *       and a {@code tenant=<slug>} query parameter is added).</li>
 * </ol>
 * The path of the reset page on the tenant origin is {@code frontend.reset-password-path}.
 */
@Component
public class TenantLinkBuilder {

    private final TenantRegistryService tenantRegistryService;
    private final TenantResolutionProperties tenantProps;
    private final OnboardingProperties onboardingProps;
    private final String fallbackResetUrl;
    private final String resetPasswordPath;
    private final String oauth2FailurePath;

    public TenantLinkBuilder(TenantRegistryService tenantRegistryService,
                             TenantResolutionProperties tenantProps,
                             OnboardingProperties onboardingProps,
                             @Value("${frontend.change-password-url:}") String fallbackResetUrl,
                             @Value("${frontend.reset-password-path:/reset-password}") String resetPasswordPath,
                             @Value("${application.oauth2.failure-path:/login}") String oauth2FailurePath) {
        this.tenantRegistryService = tenantRegistryService;
        this.tenantProps = tenantProps;
        this.onboardingProps = onboardingProps;
        this.fallbackResetUrl = fallbackResetUrl == null ? "" : fallbackResetUrl.strip();
        this.resetPasswordPath = normalizePath(resetPasswordPath);
        this.oauth2FailurePath = normalizePath(oauth2FailurePath);
    }

    /** The storefront origin ({@code scheme://host}) of the tenant, empty when it cannot be derived. */
    public Optional<String> origin(String slug) {
        if (slug == null || slug.isBlank() || TenantContext.PLATFORM_SLUG.equals(slug)) {
            return Optional.empty();
        }
        String scheme = onboardingProps.getPublicScheme();
        Optional<Tenant> tenant = tenantRegistryService.findBySlug(slug);
        if (tenant.isPresent()) {
            String custom = tenant.get().getCustomDomain();
            if (custom != null && !custom.isBlank()) {
                return Optional.of(scheme + "://" + custom.strip().toLowerCase());
            }
        }
        String base = tenantProps.getBaseDomain();
        if (tenant.isPresent() && base != null && !base.isBlank()) {
            return Optional.of(scheme + "://" + tenant.get().getSlug() + "." + base.strip().toLowerCase());
        }
        return Optional.empty();
    }

    /** The emailed password-reset link for a tenant. Throws if no tenant-aware link can be built. */
    public String resetPasswordLink(String slug, String token) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalStateException("Cannot build a password reset link without a tenant");
        }
        Optional<String> origin = origin(slug);
        UriComponentsBuilder builder;
        if (origin.isPresent()) {
            builder = UriComponentsBuilder.fromUriString(origin.get() + resetPasswordPath);
        } else if (!fallbackResetUrl.isEmpty()) {
            builder = UriComponentsBuilder.fromUriString(fallbackResetUrl.replace("{slug}", slug))
                    .queryParam("tenant", slug);
        } else {
            throw new IllegalStateException("No tenant origin and no frontend.change-password-url for tenant " + slug);
        }
        return builder.queryParam("resetPasswordToken", token).build().toUriString();
    }

    /** Where to send the browser after a failed OAuth2 login when the tenant is known: its login page. */
    public Optional<String> oauth2FailureRedirect(String slug) {
        return origin(slug).map(o -> o + oauth2FailurePath);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.strip();
        return p.startsWith("/") ? p : "/" + p;
    }
}
