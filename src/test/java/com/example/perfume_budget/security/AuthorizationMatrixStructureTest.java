package com.example.perfume_budget.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-03 structural guard: every controller mutation (POST/PUT/PATCH/DELETE) must either carry @PreAuthorize
 * (method or class) or be listed here as an explicit decision: PUBLIC (permitAll in SecurityConfig, protected by
 * another mechanism) or AUTHENTICATED_ONLY (any signed-in user of the tenant; SecurityConfig anyRequest().authenticated()).
 * Adding a controller mutation without one of those fails this test. Pure reflection, no Spring context.
 */
class AuthorizationMatrixStructureTest {

    /** Intentionally unauthenticated mutations (matcher: explicit POST permitAll in SecurityConfig). */
    static final Set<String> PUBLIC = Set.of(
            "AuthController#register", "AuthController#verifyOtp", "AuthController#verifyLoginOtp",
            "AuthController#resendOtp", "AuthController#resendLoginOtp", "AuthController#login",
            "AuthController#logout", "AuthController#refreshToken", "AuthController#forgotPassword",
            "AuthController#resetPassword",
            "WebhookController#processWebhook",
            // dev-only: bean exists only with tenant.dev-endpoints.enabled=true and SecurityConfig denyAlls the path otherwise
            "DevTenantController#createTenant",
            "PlatformAuthController#login", "PlatformAuthController#refresh", "PlatformAuthController#logout",
            "TenantOnboardingController#signup", "TenantOnboardingController#verify", "TenantOnboardingController#resend");

    /** Mutations any authenticated user may call without a role (none today: customer routes carry hasRole('CUSTOMER')). */
    static final Set<String> AUTHENTICATED_ONLY = Set.of();

    /** Intentionally unauthenticated reads (explicit GET permitAll matchers in SecurityConfig). */
    static final Set<String> PUBLIC_READS = Set.of(
            "CategoryController#getCategories", "ProductController#getFeaturedProducts",
            "ProductController#getProductListings", "ProductController#searchProducts",
            "ProductController#getProductDetailsPage", "StoreThemeController#getTheme",
            "TenantOnboardingController#status");

    /** Reads open to any signed-in user of the tenant (no role): documented decision, reviewed in the matrix doc. */
    static final Set<String> AUTHENTICATED_READS = Set.of(
            "TaxController#calculateTaxes", "TaxController#getAllTaxes",
            "AuthController#getAuthenticatedUser"); // GET /auth/me: matcher authenticated()

    @Test
    void everyControllerMutationHasPreAuthorizeOrAnExplicitDecision() throws Exception {
        List<String> undecided = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Class<?> c : controllers()) {
            boolean classLevel = AnnotatedElementUtils.hasAnnotation(c, PreAuthorize.class);
            for (Method m : c.getDeclaredMethods()) {
                if (!isMutation(m)) {
                    continue;
                }
                String id = c.getSimpleName() + "#" + m.getName();
                seen.add(id);
                boolean secured = classLevel || m.isAnnotationPresent(PreAuthorize.class);
                boolean decided = PUBLIC.contains(id) || AUTHENTICATED_ONLY.contains(id);
                if (secured && decided) {
                    undecided.add(id + " (listed in an allow-list but also has @PreAuthorize: remove from the list)");
                } else if (!secured && !decided) {
                    undecided.add(id + " (mutation without @PreAuthorize and not on PUBLIC/AUTHENTICATED_ONLY)");
                }
            }
        }
        assertThat(undecided).as("controller mutations lacking an authorization decision").isEmpty();
        // allow-lists must not rot: every entry names a real mutation
        assertThat(seen).containsAll(PUBLIC).containsAll(AUTHENTICATED_ONLY);
    }

    @Test
    void everyControllerReadHasPreAuthorizeOrAnExplicitDecision() throws Exception {
        List<String> undecided = new ArrayList<>();
        for (Class<?> c : controllers()) {
            boolean classLevel = AnnotatedElementUtils.hasAnnotation(c, PreAuthorize.class);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(GetMapping.class)) {
                    continue;
                }
                String id = c.getSimpleName() + "#" + m.getName();
                boolean secured = classLevel || m.isAnnotationPresent(PreAuthorize.class);
                boolean decided = PUBLIC_READS.contains(id) || AUTHENTICATED_READS.contains(id);
                if (secured == decided) {
                    undecided.add(id + (secured ? " (secured but listed)" : " (GET without @PreAuthorize and not on PUBLIC_READS/AUTHENTICATED_READS)"));
                }
            }
        }
        assertThat(undecided).isEmpty();
    }

    @Test
    void accountingAndPlatformControllersAreRoleGuardedAtClassLevel() throws Exception {
        for (Class<?> c : controllers()) {
            if (c.getSimpleName().equals("AccountingController")) {
                assertThat(c.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
            }
            if (c.getSimpleName().equals("PlatformTenantController")) {
                assertThat(c.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('SUPER_ADMIN')");
            }
        }
    }

    @Test
    void controllerBaselineIsDiscovered() throws Exception {
        assertThat(controllers()).extracting(Class::getSimpleName)
                .contains("CategoryController", "ProductController", "WebhookController", "DevTenantController",
                        "PlatformTenantController", "StoreThemeController");
    }

    @Test
    void categoryAndProductMutationsAreAdminOnly() throws Exception {
        for (Class<?> c : controllers()) {
            if (!c.getSimpleName().equals("CategoryController") && !c.getSimpleName().equals("ProductController")) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (isMutation(m)) {
                    PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
                    assertThat(pa).as("%s#%s", c.getSimpleName(), m.getName()).isNotNull();
                    assertThat(pa.value()).as("%s#%s", c.getSimpleName(), m.getName()).isEqualTo("hasRole('ADMIN')");
                }
            }
        }
    }

    static List<Class<?>> controllers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        // DevTenantController is @ConditionalOnProperty: evaluate it as enabled so the guard sees it
        ((org.springframework.core.env.ConfigurableEnvironment) scanner.getEnvironment()).getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("sec03", java.util.Map.of("tenant.dev-endpoints.enabled", "true")));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.perfume_budget")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.getEnclosingClass() == null) { // top-level only: ignore nested test fixtures
                out.add(c);
            }
        }
        return out;
    }

    static boolean isMutation(Method m) {
        if (m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(PatchMapping.class) || m.isAnnotationPresent(DeleteMapping.class)) {
            return true;
        }
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        if (rm != null) {
            for (var method : rm.method()) {
                if (!method.name().equals("GET") && !method.name().equals("HEAD") && !method.name().equals("OPTIONS")) {
                    return true;
                }
            }
            return rm.method().length == 0; // unrestricted RequestMapping accepts mutations
        }
        return false;
    }
}
