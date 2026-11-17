package com.example.perfume_budget.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.util.SimpleMethodInvocation;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-03 / SEC-03b structural guard (pure reflection + two source files, no Spring context).
 *
 * <p>Every controller handler (any verb, including {@code @RequestMapping(method = GET)}) is pinned to its INTENDED
 * authorization in {@link #EXPECTED}: an exact {@code @PreAuthorize} expression, {@link #PUBLIC} (no annotation and
 * permitAll in SecurityConfig) or {@link #AUTHENTICATED} (no annotation, any signed-in tenant user). The table is
 * written by hand from the product decision, NOT derived from the annotations, so a weakened expression
 * ({@code isAuthenticated()}, {@code permitAll()}, a wrong role) or a deleted annotation on an ADMIN method fails.
 *
 * <p>The SecurityConfig matchers are parsed from source and cross-checked against the table (the matcher and the
 * annotation must agree on the roles) and docs/api/authorization-matrix.md section 3 is verified row by row.
 */
class AuthorizationMatrixStructureTest {

    static final String ADMIN = "hasRole('ADMIN')";
    static final String CUSTOMER = "hasRole('CUSTOMER')";
    static final String FRONT_DESK = "hasAnyRole('ADMIN','FRONT_DESK')";
    static final String SUPER_ADMIN = "hasRole('SUPER_ADMIN')";
    /** No @PreAuthorize and permitAll in SecurityConfig. */
    static final String PUBLIC = "PUBLIC";
    /** No @PreAuthorize, any signed-in user of the tenant (matcher authenticated()/anyRequest). */
    static final String AUTHENTICATED = "AUTHENTICATED";

    /** Class#method -> intended authorization. Hand-maintained, see the class comment. */
    static final Map<String, String> EXPECTED = new TreeMap<>();

    private static void expect(String expression, String controller, String... methods) {
        for (String m : methods) {
            assertThat(EXPECTED.put(controller + "#" + m, expression)).as("duplicate table entry %s#%s", controller, m).isNull();
        }
    }

    static {
        // --- ADMIN only
        expect(ADMIN, "AccountingController", "getLedgerSummary", "getIncomeStatement", "getBalanceSheet", "getCashFlow",
                "getJournalEntries", "getEntriesByReference", "getManualEntries", "getMetadata");
        expect(ADMIN, "AdminFrontDeskManagementController", "getDefaultTemplate", "updateDefaultTemplate", "getFrontDeskUsers",
                "createFrontDeskUser", "assignUserToFrontDesk", "updateFrontDeskUser", "getUserPermissions", "updateUserPermissions");
        expect(ADMIN, "AdminMetricController", "getCustomerData", "getCustomerDetails", "getCustomerOrders", "getCouponMetrics",
                "getTopProducts", "getSiteMetric", "getDashboardMetric", "getLowStockProducts", "getTopCustomers", "getSalesAnalytics");
        expect(ADMIN, "BookkeepingController", "recordManualEntry");
        expect(ADMIN, "CategoryController", "addCategory", "updateCategory", "getCategoryById", "deleteCategory");
        expect(ADMIN, "CouponController", "createCoupon", "getCouponById", "getAll",
                "updateCoupon", "deactivateCoupon", "deleteCoupon");
        expect(ADMIN, "FeatureFlagController", "getAllFlags", "getFlag", "updateFlag");
        expect(ADMIN, "InventoryManagementController", "receiveStock", "adjustInventory", "getProductInventorySummary",
                "getProductInventoryHistory");
        expect(ADMIN, "OrderController", "getAllOrders", "updateOrderStatus", "acknowledgeRefund");
        expect(ADMIN, "PaymentSettingsController", "get", "update", "rotateWebhookToken");
        expect(ADMIN, "ProductController", "createProduct", "updateProduct", "getAdminProductListings", "getProductDetails",
                "setProductDiscount", "clearProductDiscount", "deleteProduct");
        expect(ADMIN, "ProductFamilyController", "getAllFamilies", "getAvailableUoms");
        expect(ADMIN, "SalesReportController", "getItemsSold", "getProductSales");
        expect(ADMIN, "ShopWideDiscountController", "setShopWideDiscount", "listNotEnded", "getCurrent", "deactivate");
        expect(ADMIN, "StorageLocationController", "create", "update", "updateDefaults");
        expect(ADMIN, "StoreThemeController", "put");
        expect(ADMIN, "TaxController", "addTax", "updateTax", "getTaxById", "deleteTax");

        // --- CUSTOMER only
        expect(CUSTOMER, "CartController", "getMyCart", "clearMyCart", "populateCartFromLocalStorage", "checkout", "preview");
        expect(CUSTOMER, "CartItemController", "addItemToCart", "updateCartItem", "removeItemFromCart");
        expect(CUSTOMER, "DeliveryDetailController", "addDeliveryAddress", "deleteAddress", "getMyAddresses", "setDefault");
        expect(CUSTOMER, "OrderController", "getMyOrders", "cancelOrder");
        expect(CUSTOMER, "PaymentController", "verify");
        expect("hasAnyRole('CUSTOMER','ADMIN')", "OrderController", "getOrder");

        // --- ADMIN or FRONT_DESK (the front desk works the till and the stock room)
        expect(FRONT_DESK, "InventoryManagementController", "getProductStockByLocation");
        expect(FRONT_DESK, "NotificationController", "getNotifications", "getUnreadCount", "markAsRead", "markAllAsRead");
        expect(FRONT_DESK, "ProductController", "searchAdminProducts");
        expect(FRONT_DESK, "StockConversionController", "getReverseConversionTargetVariants", "convertForward", "convertReverse");
        expect(FRONT_DESK, "StockTransferController", "transfer", "history");
        expect(FRONT_DESK, "StorageLocationController", "list", "get");
        expect(FRONT_DESK, "WalkInOrderController", "placeWalkInOrder", "searchCustomers", "getWalkInOrders", "getWalkInOrder",
                "markReceiptPrinted");

        // --- platform control plane
        expect(SUPER_ADMIN, "PlatformTenantController", "list", "detail", "audit", "approve", "suspend", "reactivate", "reprovision");
        expect(SUPER_ADMIN, "PlatformAuthController", "me");

        // --- intentionally public (permitAll in SecurityConfig; mutations are protected by another mechanism)
        expect(PUBLIC, "AuthController", "register", "verifyOtp", "verifyLoginOtp", "resendOtp", "resendLoginOtp", "login",
                "logout", "refreshToken", "forgotPassword", "resetPassword");
        expect(PUBLIC, "WebhookController", "processWebhook");                       // per-tenant token + HMAC signature
        expect(PUBLIC, "DevTenantController", "createTenant");                       // bean only when dev flag; matcher denyAll otherwise
        expect(PUBLIC, "PlatformAuthController", "login", "refresh", "logout");
        expect(PUBLIC, "TenantOnboardingController", "signup", "verify", "resend", "status");
        expect(PUBLIC, "CategoryController", "getCategories");
        expect(PUBLIC, "ProductController", "getFeaturedProducts", "getProductListings", "searchProducts", "getProductDetailsPage");
        expect(PUBLIC, "StoreThemeController", "getTheme");

        // --- any signed-in tenant user, no role. /tax and /tax/all: decided to stay open to signed-in users (SEC-03b F8):
        // the storefront does not call them, the rates are not secret (every checkout total shows them) and removing
        // access could break API clients; the admin-only rate administration (add/update/get-by-id/delete) is ADMIN above.
        expect(AUTHENTICATED, "TaxController", "calculateTaxes", "getAllTaxes");
        expect(AUTHENTICATED, "AuthController", "getAuthenticatedUser");                // GET /auth/me (annotation is commented out in source)
    }

    /** Public mutations (kept for CatalogAuthorizationMatrixIntegrationTest's anonymous sweep). */
    static final Set<String> PUBLIC_MUTATIONS = Set.of(
            "AuthController#register", "AuthController#verifyOtp", "AuthController#verifyLoginOtp",
            "AuthController#resendOtp", "AuthController#resendLoginOtp", "AuthController#login",
            "AuthController#logout", "AuthController#refreshToken", "AuthController#forgotPassword",
            "AuthController#resetPassword", "WebhookController#processWebhook", "DevTenantController#createTenant",
            "PlatformAuthController#login", "PlatformAuthController#refresh", "PlatformAuthController#logout",
            "TenantOnboardingController#signup", "TenantOnboardingController#verify", "TenantOnboardingController#resend");

    /** Public reads. */
    static final Set<String> PUBLIC_READS = Set.of(
            "CategoryController#getCategories", "ProductController#getFeaturedProducts",
            "ProductController#getProductListings", "ProductController#searchProducts",
            "ProductController#getProductDetailsPage", "StoreThemeController#getTheme",
            "TenantOnboardingController#status");

    // ---------------------------------------------------------------- handler model

    record Handler(Class<?> controller, Method method, String id, Set<String> verbs, boolean unrestricted, String path, String preAuthorize) {
        boolean mutation() {
            return verbs.stream().anyMatch(v -> !v.equals("GET") && !v.equals("HEAD") && !v.equals("OPTIONS"));
        }
    }

    /** Every request-mapped method of the controllers: @GetMapping & co AND @RequestMapping(method = ...). */
    static List<Handler> handlers(List<Class<?>> controllers) {
        List<Handler> out = new ArrayList<>();
        for (Class<?> c : controllers) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String base = classMapping != null && classMapping.path().length > 0 ? classMapping.path()[0] : "";
            PreAuthorize classLevel = AnnotatedElementUtils.findMergedAnnotation(c, PreAuthorize.class);
            // own AND inherited handler methods (an abstract base class may declare the mapping)
            for (Method m : ReflectionUtils.getUniqueDeclaredMethods(c, mth -> !mth.isBridge() && !mth.isSynthetic())) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null) {
                    continue;
                }
                Set<String> verbs = new LinkedHashSet<>();
                for (RequestMethod rm : mm.method()) {
                    verbs.add(rm.name());
                }
                boolean unrestricted = verbs.isEmpty();
                if (verbs.isEmpty()) {
                    // an unrestricted mapping answers every verb: treat it as a mutation (and as a GET)
                    verbs.addAll(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
                }
                String sub = mm.path().length > 0 ? mm.path()[0] : "";
                PreAuthorize own = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                PreAuthorize effective = own != null ? own : classLevel;
                out.add(new Handler(c, m, c.getSimpleName() + "#" + m.getName(), verbs, unrestricted, base + sub,
                        effective == null ? null : effective.value()));
            }
        }
        return out;
    }

    static String norm(String expression) {
        return expression == null ? null : expression.replaceAll("\\s+", "");
    }

    // ---------------------------------------------------------------- tests

    @Test
    void everyHandlerIsPinnedToItsIntendedAuthorization() throws Exception {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Handler h : handlers(controllers())) {
            seen.add(h.id());
            String expected = EXPECTED.get(h.id());
            if (expected == null) {
                problems.add(h.id() + ": no entry in EXPECTED (decide its authorization and add it)");
            } else if (expected.equals(PUBLIC) || expected.equals(AUTHENTICATED)) {
                if (h.preAuthorize() != null) {
                    problems.add(h.id() + ": expected no @PreAuthorize (" + expected + ") but has " + h.preAuthorize());
                }
            } else if (!norm(expected).equals(norm(h.preAuthorize()))) {
                problems.add(h.id() + ": expected @PreAuthorize " + expected + " but is " + h.preAuthorize());
            }
        }
        assertThat(problems).as("authorization drifted from the intended table").isEmpty();
        assertThat(seen).as("EXPECTED must not name methods that no longer exist").containsAll(EXPECTED.keySet());
    }

    @Test
    void theTableCanNeverBlessAWeakExpression() {
        Set<String> allowed = Set.of(ADMIN, CUSTOMER, FRONT_DESK, SUPER_ADMIN, "hasAnyRole('CUSTOMER','ADMIN')", PUBLIC, AUTHENTICATED);
        assertThat(EXPECTED.values()).isSubsetOf(allowed);
        // every non-public, non-authenticated expression names a role
        EXPECTED.values().stream().filter(v -> !v.equals(PUBLIC) && !v.equals(AUTHENTICATED))
                .forEach(v -> assertThat(v).contains("Role("));
    }

    @Test
    void publicAndAuthenticatedEntriesAreAnExplicitDecision() throws Exception {
        Set<String> publicIds = new TreeSet<>();
        EXPECTED.forEach((id, v) -> {
            if (v.equals(PUBLIC)) {
                publicIds.add(id);
            }
        });
        Set<String> declared = new TreeSet<>(PUBLIC_MUTATIONS);
        declared.addAll(PUBLIC_READS);
        assertThat(publicIds).isEqualTo(declared);
        for (Handler h : handlers(controllers())) {
            if (PUBLIC_MUTATIONS.contains(h.id())) {
                assertThat(h.mutation()).as("%s is listed as a public mutation", h.id()).isTrue();
            }
            if (PUBLIC_READS.contains(h.id())) {
                assertThat(h.mutation()).as("%s is listed as a public read", h.id()).isFalse();
            }
        }
    }

    @Test
    void controllerBaselineIsDiscovered() throws Exception {
        List<Class<?>> controllers = controllers();
        assertThat(controllers).extracting(Class::getSimpleName)
                .contains("CategoryController", "ProductController", "WebhookController", "DevTenantController",
                        "PlatformTenantController", "StoreThemeController", "TaxController", "AuthController");
        assertThat(handlers(controllers)).hasSizeGreaterThanOrEqualTo(135);
    }

    // --- the old tests' guarantees, now table-driven

    @Test
    void everyMutationIsGuardedOrExplicitlyPublic() throws Exception {
        for (Handler h : handlers(controllers())) {
            if (h.mutation() && h.preAuthorize() == null) {
                assertThat(PUBLIC_MUTATIONS).as("mutation %s has no @PreAuthorize and is not on the public list", h.id()).contains(h.id());
            }
        }
    }

    @Test
    void requestMappingGetReadsAreScannedToo() {
        @RestController
        @RequestMapping("/x")
        class Fixture {
            @RequestMapping(value = "/viaRequestMapping", method = RequestMethod.GET)
            public String viaRequestMapping() { return ""; }

            @GetMapping("/viaGetMapping")
            public String viaGetMapping() { return ""; }

            @RequestMapping("/anyVerb")
            public String anyVerb() { return ""; }
        }
        List<Handler> found = handlers(List.of(Fixture.class));
        assertThat(found).extracting(Handler::id).containsExactlyInAnyOrder(
                "Fixture#viaRequestMapping", "Fixture#viaGetMapping", "Fixture#anyVerb");
        Map<String, Handler> byName = new LinkedHashMap<>();
        found.forEach(h -> byName.put(h.method().getName(), h));
        assertThat(byName.get("viaRequestMapping").mutation()).isFalse();
        assertThat(byName.get("viaRequestMapping").path()).isEqualTo("/x/viaRequestMapping");
        assertThat(byName.get("anyVerb").mutation()).isTrue();
    }

    // ---------------------------------------------------------------- SecurityConfig vs annotations

    enum Kind { PERMIT, AUTH, ROLES, DENY, SPECIAL }

    record Rule(String verb, List<String> patterns, Kind kind, Set<String> roles) {
        boolean matches(String verb, String path) {
            if (this.verb != null && !this.verb.equals(verb)) {
                return false;
            }
            AntPathMatcher ant = new AntPathMatcher();
            return patterns.stream().anyMatch(p -> ant.match(p, path));
        }
    }

    private static final Pattern RULE = Pattern.compile(
            "\\.requestMatchers\\((?:HttpMethod\\.(\\w+),\\s*)?((?:\"[^\"]*\"\\s*,?\\s*)+)\\)\\s*\\.(permitAll|authenticated|denyAll|hasRole|hasAnyRole|access)\\(([^)]*)");

    /** Matchers of SecurityConfig in declaration order (string-pattern matchers only; the swagger/dev lambdas are SPECIAL or skipped). */
    static String securityConfigSource() throws Exception {
        return Files.readString(Path.of("src/main/java/com/example/perfume_budget/security/SecurityConfig.java"));
    }

    static List<Rule> securityConfigRules() throws Exception {
        List<Rule> rules = securityConfigRules(securityConfigSource());
        assertThat(auditAuthorizeBlock(securityConfigSource())).as("SecurityConfig uses only matcher styles this test understands").isEmpty();
        return rules;
    }

    /** The two lambda rules that gate swagger/api-docs on the springdoc flags (public only while springdoc is enabled). */
    static final List<String> ALLOWED_LAMBDA_RULES = List.of(
            ".requestMatchers(request -> swaggerUiEnabled && isReadMethod(request) && matchesAny(request, swaggerPaths)).permitAll()",
            ".requestMatchers(request -> apiDocsEnabled && isReadMethod(request) && matchesAny(request, apiDocsPaths)).permitAll()");

    private static String authorizeBlock(String src) {
        String clean = src.replaceAll("//[^\n]*", "").replaceAll("\\s+", " ");
        int a = clean.indexOf(".authorizeHttpRequests(");
        int b = clean.indexOf(".oauth2Login(");
        assertThat(a).as("authorizeHttpRequests block").isGreaterThanOrEqualTo(0);
        assertThat(b).as("end of authorizeHttpRequests block").isGreaterThan(a);
        return clean.substring(a, b);
    }

    /**
     * Fails (non-empty result) on anything the rule parser could silently drop: a lambda / custom RequestMatcher, an
     * unknown authorization method, securityMatchers, an anyRequest() that is not the single final
     * authenticated() rule, or a count mismatch between matcher occurrences and parsed rules.
     */
    static List<String> auditAuthorizeBlock(String src) {
        List<String> problems = new ArrayList<>();
        String block = authorizeBlock(src);
        String normalizedBlock = block.replaceAll("\\s+", " ");
        for (String lambda : ALLOWED_LAMBDA_RULES) {
            normalizedBlock = normalizedBlock.replace(lambda.replaceAll("\\s+", " "), " ");
        }
        int allowedLambdas = (block.length() - normalizedBlock.length() > 0)
                ? (int) ALLOWED_LAMBDA_RULES.stream().filter(l -> block.contains(l)).count() : 0;
        int requestMatchers = count(normalizedBlock, "requestMatchers(");
        List<Rule> parsed = securityConfigRules(src);
        if (requestMatchers != parsed.size()) {
            problems.add("requestMatchers( occurrences (" + requestMatchers + ") != parsed rules (" + parsed.size()
                    + "): a matcher style the parser cannot read (lambda/custom RequestMatcher?)");
        }
        for (String banned : List.of("securityMatcher", "mvcMatchers", "antMatchers", "regexMatchers", "hasAuthority", "hasAnyAuthority",
                "hasIpAddress", "fullyAuthenticated", "rememberMe", "anonymous()", "access(request")) {
            if (normalizedBlock.contains(banned)) {
                problems.add("unsupported construct in the authorization block: " + banned);
            }
        }
        int anyRequest = count(normalizedBlock, "anyRequest()");
        if (anyRequest != 1) {
            problems.add("expected exactly one anyRequest() rule but found " + anyRequest);
        } else {
            String tail = normalizedBlock.substring(normalizedBlock.indexOf("anyRequest()"));
            if (!tail.replaceAll("\\s+", "").matches("anyRequest\\(\\)\\.authenticated\\(\\)\\)")) {
                problems.add("anyRequest() must be the last rule and be .authenticated(); found: " + tail);
            }
        }
        int decisions = 0;
        Matcher dm = Pattern.compile("\\.(permitAll|authenticated|denyAll|hasRole|hasAnyRole|access)\\(").matcher(normalizedBlock);
        while (dm.find()) {
            decisions++;
        }
        if (decisions != parsed.size() + 1) { // every parsed rule + the anyRequest().authenticated()
            problems.add("authorization decisions (" + decisions + ") != parsed rules + anyRequest (" + (parsed.size() + 1) + ")");
        }
        assertThat(allowedLambdas).as("allowed lambda rules present").isBetween(0, ALLOWED_LAMBDA_RULES.size());
        return problems;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    static List<Rule> securityConfigRules(String sourceText) {
        String src = sourceText.replaceAll("//[^\n]*", "");
        List<Rule> rules = new ArrayList<>();
        Matcher m = RULE.matcher(src);
        while (m.find()) {
            List<String> patterns = new ArrayList<>();
            Matcher q = Pattern.compile("\"([^\"]*)\"").matcher(m.group(2));
            while (q.find()) {
                patterns.add(q.group(1));
            }
            Set<String> roles = new TreeSet<>();
            Matcher r = Pattern.compile("\"([A-Z_]+)\"").matcher(m.group(4));
            while (r.find()) {
                roles.add(r.group(1));
            }
            Kind kind = switch (m.group(3)) {
                case "permitAll" -> Kind.PERMIT;
                case "authenticated" -> Kind.AUTH;
                case "denyAll" -> Kind.DENY;
                case "access" -> Kind.SPECIAL;
                default -> Kind.ROLES;
            };
            rules.add(new Rule(m.group(1), patterns, kind, roles));
        }
        return rules;
    }

    /** First matching rule, else the anyRequest().authenticated() default. */
    static Rule ruleFor(List<Rule> rules, String verb, String path) {
        return rules.stream().filter(r -> r.matches(verb, path)).findFirst()
                .orElse(new Rule(null, List.of("/**"), Kind.AUTH, Set.of()));
    }

    static Set<String> rolesOf(String expression) {
        Set<String> roles = new TreeSet<>();
        Matcher m = Pattern.compile("'([A-Z_]+)'").matcher(expression);
        while (m.find()) {
            roles.add(m.group(1));
        }
        return roles;
    }

    @Test
    void securityConfigMatchersAgreeWithTheAnnotations() throws Exception {
        List<Rule> rules = securityConfigRules();
        assertThat(rules).as("SecurityConfig parse found its matchers").hasSizeGreaterThan(25);
        List<String> problems = new ArrayList<>();
        for (Handler h : handlers(controllers())) {
            String expected = EXPECTED.get(h.id());
            String path = h.path().replaceAll("\\{[^}]+}", "x");
            for (String verb : h.verbs()) {
                if (h.unrestricted()) {
                    continue; // unrestricted mapping (every verb): covered by the table check, not a concrete route
                }
                Rule rule = ruleFor(rules, verb, path);
                String where = verb + " " + path + " (" + h.id() + ") matcher " + rule.kind() + rule.roles();
                switch (rule.kind()) {
                    case PERMIT -> {
                        if (!PUBLIC.equals(expected)) {
                            problems.add(where + " is permitAll but the intended authorization is " + expected);
                        }
                    }
                    case ROLES -> {
                        if (PUBLIC.equals(expected) || AUTHENTICATED.equals(expected)
                                || !rolesOf(expected).equals(rule.roles())) {
                            problems.add(where + " disagrees with the intended " + expected);
                        }
                    }
                    case AUTH -> {
                        if (PUBLIC.equals(expected)) {
                            problems.add(where + " requires authentication but the endpoint is intended to be public");
                        }
                    }
                    case DENY, SPECIAL -> {
                        if (!h.controller().getSimpleName().equals("DevTenantController")) {
                            problems.add(where + " is denied/special-cased for a normal endpoint");
                        }
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    /** F7: HEAD is answered from the GET handler, so every GET rule must have an identical HEAD rule. */
    @Test
    void headFollowsGetForEveryStringMatcherRule() throws Exception {
        List<Rule> rules = securityConfigRules();
        List<String> problems = new ArrayList<>();
        for (Rule get : rules) {
            if (!"GET".equals(get.verb()) || get.kind() == Kind.SPECIAL) {
                continue;
            }
            boolean twin = rules.stream().anyMatch(r -> "HEAD".equals(r.verb()) && r.patterns().equals(get.patterns())
                    && r.kind() == get.kind() && r.roles().equals(get.roles()));
            if (!twin) {
                problems.add("GET " + get.patterns() + " " + get.kind() + get.roles() + " has no identical HEAD rule");
            }
        }
        assertThat(problems).isEmpty();
    }

    // ---------------------------------------------------------------- role x endpoint matrix derived from the code

    private static boolean expressionAllows(DefaultMethodSecurityExpressionHandler handler, String expression, Method method, String role) {
        Authentication auth = new UsernamePasswordAuthenticationToken("actor", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        EvaluationContext ctx = handler.createEvaluationContext(() -> auth, new SimpleMethodInvocation(new Object(), method));
        Expression parsed = handler.getExpressionParser().parseExpression(expression);
        return ExpressionUtils.evaluateAsBoolean(parsed, ctx);
    }

    /**
     * SEC-03b F4: for every handler and every tenant role (ADMIN, CUSTOMER, FRONT_DESK) the access that results from the
     * SecurityConfig matcher AND the evaluated @PreAuthorize expression must equal the intended table. This is the
     * FRONT_DESK row of the matrix for all admin routes, derived from the annotations (no HTTP, no side effects).
     */
    @Test
    void roleMatrixDerivedFromMatchersAndAnnotationsEqualsTheIntendedTable() throws Exception {
        List<Rule> rules = securityConfigRules();
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        List<String> problems = new ArrayList<>();
        int cells = 0;
        for (Handler h : handlers(controllers())) {
            if (h.unrestricted() || h.controller().getSimpleName().equals("DevTenantController")) {
                continue;
            }
            String expected = EXPECTED.get(h.id());
            String path = h.path().replaceAll("\\{[^}]+}", "x");
            for (String role : List.of("ADMIN", "CUSTOMER", "FRONT_DESK")) {
                boolean intended = expected.equals(PUBLIC) || expected.equals(AUTHENTICATED) || rolesOf(expected).contains(role);
                if (expected.equals(SUPER_ADMIN)) {
                    intended = false; // platform routes: a tenant role never passes
                }
                for (String verb : h.verbs()) {
                    Rule rule = ruleFor(rules, verb, path);
                    boolean matcherAllows = switch (rule.kind()) {
                        case PERMIT, AUTH -> true;
                        case ROLES -> rule.roles().contains(role);
                        case DENY, SPECIAL -> false;
                    };
                    boolean annotationAllows = h.preAuthorize() == null
                            || expressionAllows(handler, h.preAuthorize(), h.method(), role);
                    cells++;
                    if ((matcherAllows && annotationAllows) != intended) {
                        problems.add(role + " on " + verb + " " + path + " (" + h.id() + "): allowed="
                                + (matcherAllows && annotationAllows) + " but intended=" + intended);
                    }
                }
            }
        }
        assertThat(cells).as("matrix actually evaluated").isGreaterThan(400);
        assertThat(problems).isEmpty();
    }

    @Test
    void frontDeskReachesExactlyTheDocumentedOperationalRoutes() throws Exception {
        Set<String> frontDesk = new TreeSet<>();
        EXPECTED.forEach((id, v) -> {
            if (v.contains("FRONT_DESK")) {
                frontDesk.add(id);
            }
        });
        assertThat(frontDesk).containsExactlyInAnyOrder(
                "InventoryManagementController#getProductStockByLocation",
                "NotificationController#getNotifications", "NotificationController#getUnreadCount",
                "NotificationController#markAsRead", "NotificationController#markAllAsRead",
                "ProductController#searchAdminProducts",
                "StockConversionController#getReverseConversionTargetVariants", "StockConversionController#convertForward",
                "StockConversionController#convertReverse",
                "StockTransferController#transfer", "StockTransferController#history",
                "StorageLocationController#list", "StorageLocationController#get",
                "WalkInOrderController#placeWalkInOrder", "WalkInOrderController#searchCustomers",
                "WalkInOrderController#getWalkInOrders", "WalkInOrderController#getWalkInOrder",
                "WalkInOrderController#markReceiptPrinted");
    }

    // ---------------------------------------------------------------- the documented matrix

    @Test
    void authorizationMatrixDocumentMatchesReflectionRowForRow() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("docs/api/authorization-matrix.md"));
        int start = lines.indexOf("## 3. Every controller endpoint");
        assertThat(start).as("section 3 heading").isGreaterThan(0);
        Map<String, String> documented = new LinkedHashMap<>();
        Map<String, String> documentedMatcher = new LinkedHashMap<>();
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) {
            String line = lines.get(i);
            if (!line.startsWith("| ") || line.startsWith("| Controller") || line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.split("\\s\\|\\s", -1);
            assertThat(cells.length).as(line).isGreaterThanOrEqualTo(6);
            String controller = cells[0].replace("| ", "").trim();
            String verb = cells[1].trim();
            String path = cells[2].replace("`", "").trim();
            String matcher = cells[3].trim();
            String pre = cells[4].replace("`", "").replace("(class)", "").trim();
            String key = controller + " " + verb + " " + path;
            assertThat(documented.put(key, pre)).as("duplicate doc row " + key).isNull();
            documentedMatcher.put(key, matcher);
        }
        List<Rule> rules = securityConfigRules();
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> actualMatcher = new LinkedHashMap<>();
        for (Handler h : handlers(controllers())) {
            for (String verb : h.verbs()) {
                if (h.unrestricted()) {
                    continue;
                }
                String key = h.controller().getSimpleName() + " " + verb + " " + h.path();
                actual.put(key, h.preAuthorize() == null ? "-" : h.preAuthorize());
                Rule r = ruleFor(rules, verb, h.path().replaceAll("\\{[^}]+}", "x"));
                actualMatcher.put(key, r.kind().name() + r.roles());
            }
        }
        assertThat(documented.keySet()).as("rows of the matrix doc vs controllers").isEqualTo(actual.keySet());
        List<String> problems = new ArrayList<>();
        documented.forEach((k, v) -> {
            if (!norm(v).equals(norm(actual.get(k)))) {
                problems.add(k + ": doc @PreAuthorize `" + v + "` but source has `" + actual.get(k) + "`");
            }
            String m = documentedMatcher.get(k).toLowerCase();
            String a = actualMatcher.get(k);
            boolean ok;
            if (a.startsWith("PERMIT")) {
                ok = m.startsWith("permitall");
            } else if (a.startsWith("AUTH")) {
                ok = m.startsWith("authenticated");
            } else if (a.startsWith("DENY") || a.startsWith("SPECIAL")) {
                ok = m.startsWith("denyall");
            } else {
                Set<String> roles = new TreeSet<>();
                Matcher r = Pattern.compile("[a-z_]+").matcher(m.replaceAll("\\(.*", ""));
                while (r.find()) {
                    roles.add(r.group().toUpperCase());
                }
                ok = a.equals("ROLES" + roles);
            }
            if (!ok) {
                problems.add(k + ": doc matcher `" + documentedMatcher.get(k) + "` but SecurityConfig gives " + a);
            }
        });
        assertThat(problems).isEmpty();
    }

    // ---------------------------------------------------------------- self-tests: the guard itself cannot be bypassed

    // Fixtures (never registered as beans: the profile is never active)
    abstract static class AbstractBaseFixture {
        @GetMapping("/inh")
        public String inheritedHandler() { return ""; }
    }

    @RestController
    @Profile("sec03b-fixture-never")
    static class ConcreteFixture extends AbstractBaseFixture {
    }

    @RestController
    @Profile("sec03b-fixture-never")
    @RequestMapping("/nested")
    static class NestedRestFixture {
        @GetMapping("/n")
        public String nestedHandler() { return ""; }
    }

    @Controller
    @ResponseBody
    @Profile("sec03b-fixture-never")
    static class PlainControllerFixture {
        @GetMapping("/c")
        public String plainControllerHandler() { return ""; }
    }

    @Test
    void s4_handlersInheritedFromAnAbstractBaseClassAreSeen() {
        assertThat(handlers(List.of(ConcreteFixture.class))).extracting(Handler::id).containsExactly("ConcreteFixture#inheritedHandler");
    }

    @Test
    void s5_s6_nestedClassesAndPlainControllersAreDiscovered_butTestFixturesAreNotProduction() throws Exception {
        List<Class<?>> all = scan("com.example.perfume_budget.security", false);
        assertThat(all).contains(NestedRestFixture.class, PlainControllerFixture.class, ConcreteFixture.class);
        assertThat(controllers()).doesNotContain(NestedRestFixture.class, PlainControllerFixture.class, ConcreteFixture.class);
        // a handler on each fixture is reachable by the table check (so it would demand an EXPECTED entry)
        assertThat(handlers(List.of(NestedRestFixture.class, PlainControllerFixture.class)))
                .extracting(Handler::id).containsExactlyInAnyOrder("NestedRestFixture#nestedHandler", "PlainControllerFixture#plainControllerHandler");
    }

    private static final String BASE_RULE_BLOCK =
            ".authorizeHttpRequests(auth -> auth .requestMatchers(HttpMethod.GET, \"/a\").permitAll() "
                    + ".requestMatchers(\"/b/**\").hasRole(\"ADMIN\") .anyRequest().authenticated() ) .oauth2Login(";

    @Test
    void s8_theSourceAuditAcceptsTheRealConfigAndRejectsEverythingTheParserCannotSee() throws Exception {
        assertThat(auditAuthorizeBlock(securityConfigSource())).isEmpty();
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK)).isEmpty();
        // lambda permitAll placed before the role rule: invisible to the regex, caught by the count
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK.replace(".requestMatchers(\"/b/**\")",
                ".requestMatchers(r -> r.getRequestURI().startsWith(\"/b\")).permitAll() .requestMatchers(\"/b/**\")"))).isNotEmpty();
        // custom RequestMatcher object
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK.replace(".requestMatchers(\"/b/**\")",
                ".requestMatchers(new AntPathRequestMatcher(\"/b/**\")).permitAll() .requestMatchers(\"/b/**\")"))).isNotEmpty();
        // anyRequest().permitAll() / anyRequest in the middle / a second anyRequest
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK.replace("anyRequest().authenticated()", "anyRequest().permitAll()"))).isNotEmpty();
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK.replace(".requestMatchers(\"/b/**\")",
                ".anyRequest().permitAll() .requestMatchers(\"/b/**\")"))).isNotEmpty();
        // securityMatchers / access(request...) / hasAuthority
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK.replace(".hasRole(\"ADMIN\")", ".hasAuthority(\"X\")"))).isNotEmpty();
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK.replace(".requestMatchers(\"/b/**\")",
                ".securityMatchers(\"/z\") .requestMatchers(\"/b/**\")"))).isNotEmpty();
        // a rule appended after anyRequest
        assertThat(auditAuthorizeBlock("x " + BASE_RULE_BLOCK.replace(" ) .oauth2Login(", " .requestMatchers(\"/late\").permitAll() ) .oauth2Login("))).isNotEmpty();
    }

    @Test
    void actuatorRulesAreStrictlyPinned() throws Exception {
        List<Rule> rules = securityConfigRules();
        assertThat(ruleFor(rules, "GET", "/actuator/health").kind()).isEqualTo(Kind.PERMIT);
        assertThat(ruleFor(rules, "HEAD", "/actuator/health").kind()).isEqualTo(Kind.PERMIT);
        assertThat(ruleFor(rules, "GET", "/actuator/prometheus").kind()).isEqualTo(Kind.SPECIAL);
        for (String p : List.of("metrics", "info", "env", "beans", "loggers", "heapdump", "health/liveness")) {
            for (String verb : List.of("GET", "HEAD", "POST")) {
                assertThat(ruleFor(rules, verb, "/actuator/" + p).kind()).as(verb + " /actuator/" + p).isEqualTo(Kind.DENY);
            }
        }
    }

    @Test
    void scrapeTokenMatches_isStrict() {
        String t16 = "0123456789abcdef";
        assertThat(SecurityConfig.scrapeTokenMatches(t16, "Bearer " + t16)).isTrue();
        assertThat(SecurityConfig.scrapeTokenMatches(t16 + "0123456789abcdef", "Bearer " + t16 + "0123456789abcdef")).isTrue();
        // minimum length 16: 15 and 14 are not usable tokens even when the header matches them
        assertThat(SecurityConfig.scrapeTokenMatches(t16.substring(1), "Bearer " + t16.substring(1))).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16.substring(2), "Bearer " + t16.substring(2))).isFalse();
        // blank / null / whitespace-only configuration never matches, even "Bearer " + the same blanks
        for (String blank : new String[]{null, "", " ", "                ", "\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t"}) {
            assertThat(SecurityConfig.scrapeTokenMatches(blank, "Bearer " + (blank == null ? "" : blank))).as("configured=[" + blank + "]").isFalse();
        }
        // scheme is case-sensitive (pinned): Prometheus sends "Bearer"
        assertThat(SecurityConfig.scrapeTokenMatches(t16, "bearer " + t16)).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16, "BEARER " + t16)).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16, "Basic " + t16)).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16, t16)).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16, "Bearer  " + t16)).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16, "Bearer " + t16 + " ")).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16, null)).isFalse();
        assertThat(SecurityConfig.scrapeTokenMatches(t16, "Bearer ")).isFalse();
    }

    // ---------------------------------------------------------------- discovery

    /** Production controllers: every {@code @Controller} (which includes {@code @RestController}), top-level AND nested. */
    static List<Class<?>> controllers() throws Exception {
        return scan("com.example.perfume_budget", true);
    }

    static List<Class<?>> scan(String basePackage, boolean productionOnly) throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class)); // meta-annotations (@RestController) included
        // DevTenantController is @ConditionalOnProperty: evaluate it as enabled so the guard sees it
        ((org.springframework.core.env.ConfigurableEnvironment) scanner.getEnvironment()).getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("sec03", java.util.Map.of("tenant.dev-endpoints.enabled", "true")));
        // the self-test fixtures are @Profile-guarded so the application context never registers them; this scanner opts in
        ((org.springframework.core.env.ConfigurableEnvironment) scanner.getEnvironment()).addActiveProfile("sec03b-fixture-never");
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (productionOnly) {
                String location = c.getProtectionDomain().getCodeSource().getLocation().getPath();
                if (location.contains("test-classes")) {
                    continue; // test fixtures are not production controllers
                }
            }
            out.add(c);
        }
        return out;
    }

    static boolean isMutation(Method m) {
        RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
        if (mm == null) {
            return false;
        }
        return mm.method().length == 0 || Arrays.stream(mm.method()).anyMatch(
                v -> v != RequestMethod.GET && v != RequestMethod.HEAD && v != RequestMethod.OPTIONS);
    }
}
