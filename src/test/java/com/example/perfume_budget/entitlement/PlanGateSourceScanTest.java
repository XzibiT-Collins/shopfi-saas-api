package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN-02 static half of the registry and of the allow-list (plans.md 18 items 8 and 10), no Spring:
 * <ul>
 *   <li>every plan check in the production code ({@code entitlementService.requireRoute / requireWrite /
 *       requireLimitSlot / precheckLimitSlot}) is listed here with its enclosing method, and the features they use are
 *       exactly the features of the {@code @RequiresFeature} registry, so a new gate without a registry entry (and so
 *       without a test row) fails the build;</li>
 *   <li>core controllers carry no marker at all, and the never-gated handlers of mixed controllers carry none;</li>
 *   <li>no scheduled / async job, event listener, webhook, payment, checkout, cart, order, auth, onboarding,
 *       notification, metrics or ledger-posting code path references the enforcement at all.</li>
 * </ul>
 */
class PlanGateSourceScanTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/perfume_budget");
    private static final Pattern CALL = Pattern.compile(
            "entitlementService\\.(requireRoute|requireRead|requireWrite|requireLimitSlot|precheckLimitSlot)\\(PlanFeature\\.(\\w+)\\)");
    private static final Pattern METHOD = Pattern.compile("^    (?:public |private |protected |static |final |synchronized )*\\w[\\w<>\\[\\], ?.]* (\\w+)\\(");

    /** Class#method -> the features it checks. Hand-maintained: a new call site must be added here (and to the registry). */
    static final Map<String, String> CALL_SITES = new TreeMap<>(Map.ofEntries(
            Map.entry("AccountingReportServiceImpl#getAccountingMetadata", "ACCOUNTING"),
            Map.entry("AccountingReportServiceImpl#getBalanceSheet", "ACCOUNTING"),
            Map.entry("AccountingReportServiceImpl#getCashFlow", "ACCOUNTING"),
            Map.entry("AccountingReportServiceImpl#getIncomeStatement", "ACCOUNTING"),
            Map.entry("AccountingReportServiceImpl#getJournalEntries", "ACCOUNTING"),
            Map.entry("AccountingReportServiceImpl#getJournalEntriesByReference", "ACCOUNTING"),
            Map.entry("AccountingReportServiceImpl#getLedgerSummary", "ACCOUNTING"),
            Map.entry("AccountingReportServiceImpl#getManualEntries", "ACCOUNTING"),
            Map.entry("AdminFrontDeskManagementServiceImpl#assignUserToFrontDesk", "MAX_STAFF"),
            Map.entry("AdminFrontDeskManagementServiceImpl#createFrontDeskUser", "MAX_STAFF"),
            Map.entry("AdminFrontDeskManagementServiceImpl#updateFrontDeskUser", "MAX_STAFF"),
            Map.entry("BookkeepingService#recordManualEntry", "ACCOUNTING"),
            Map.entry("CouponServiceImpl#createCoupon", "MAX_ACTIVE_COUPONS"),
            Map.entry("CouponServiceImpl#updateCoupon", "MAX_ACTIVE_COUPONS"),
            Map.entry("CustomDomainService#requirePlan", "CUSTOM_DOMAIN"),
            Map.entry("FeatureFlagServiceImpl#updateFlag", "LOGIN_OTP"),
            Map.entry("InventoryManagementServiceImpl#adjustInventory", "INVENTORY_MANAGEMENT"),
            Map.entry("InventoryManagementServiceImpl#getProductInventoryHistory", "INVENTORY_MANAGEMENT"),
            Map.entry("ProductFamilyServiceImpl#getAllFamilies", "PRODUCT_FAMILIES"),
            Map.entry("ProductFamilyServiceImpl#getAvailableUoms", "PRODUCT_FAMILIES"),
            Map.entry("ProductServiceImpl#createProduct", "MAX_PRODUCTS,PRODUCT_FAMILIES"),
            Map.entry("ProductServiceImpl#createProductRows", "MAX_PRODUCTS"),
            Map.entry("ProductServiceImpl#updateProduct", "PRODUCT_FAMILIES"),
            Map.entry("SalesReportServiceImpl#getItemsSold", "SALES_REPORTS"),
            Map.entry("ShopWideDiscountServiceImpl#setShopWideDiscount", "SHOP_WIDE_DISCOUNTS"),
            Map.entry("StockConversionServiceImpl#convertForward", "STOCK_CONVERSION"),
            Map.entry("StockConversionServiceImpl#convertReverse", "STOCK_CONVERSION"),
            Map.entry("StockConversionServiceImpl#getReverseConversionTargetVariants", "STOCK_CONVERSION"),
            Map.entry("StockTransferServiceImpl#history", "STOCK_TRANSFERS"),
            Map.entry("StockTransferServiceImpl#transfer", "STOCK_TRANSFERS"),
            Map.entry("StorageLocationServiceImpl#create", "MAX_LOCATIONS"),
            Map.entry("TaxServiceImpl#createTax", "TAX_CONFIG"),
            Map.entry("TaxServiceImpl#deleteTax", "TAX_CONFIG"),
            Map.entry("TaxServiceImpl#updateTax", "TAX_CONFIG"),
            Map.entry("ThemePlanGate#check", "THEME_ADVANCED"),
            Map.entry("WalkInOrderServiceImpl#placeWalkInOrder", "WALK_IN_POS"),
            Map.entry("WalkInOrderServiceImpl#quote", "WALK_IN_POS"),
            Map.entry("WalkInOrderServiceImpl#searchCustomers", "WALK_IN_POS")));

    /** Controllers with no plan gate at all (core for every plan, plans.md section 2). */
    static final Set<String> CORE_CONTROLLERS = Set.of("AuthController", "TenantOnboardingController", "PlatformAuthController",
            "PlatformPlanController", "PlatformTenantController", "PlatformTenantDomainController", "WebhookController",
            "PaymentController", "PaymentSettingsController", "CartController", "CartItemController", "OrderController",
            "NotificationController", "AdminMetricController", "CategoryController", "DeliveryDetailController",
            "SubscriptionController");

    /** Handlers of mixed controllers that must stay ungated (finishing orders, core inventory, reads that stay open, ...). */
    static final Set<String> NEVER_GATED_HANDLERS = Set.of(
            "WalkInOrderController#getWalkInOrders", "WalkInOrderController#getWalkInOrder",
            "WalkInOrderController#markReceiptPrinted", "WalkInOrderController#cancelWalkInOrder",
            "WalkInOrderController#refundWalkInOrder",
            "InventoryManagementController#receiveStock", "InventoryManagementController#getProductInventorySummary",
            "InventoryManagementController#getProductStockByLocation", "InventoryManagementController#legacyBalanceReport",
            "InventoryManagementController#repairLegacyBalances",
            "SalesReportController#getProductSales",
            "TaxController#calculateTaxes", "TaxController#getAllTaxes", "TaxController#getTaxById",
            "TenantCustomDomainController#getCustomDomain", "TenantCustomDomainController#removeCustomDomain",
            "ShopWideDiscountController#listNotEnded", "ShopWideDiscountController#getCurrent",
            "ShopWideDiscountController#deactivate", // PO 2026-11-28: ending a running discount is core
            "FeatureFlagController#getAllFlags", "FeatureFlagController#getFlag",
            "CouponController#getCouponById", "CouponController#getAll", "CouponController#deactivateCoupon",
            "CouponController#deleteCoupon",
            "AdminFrontDeskManagementController#getFrontDeskUsers", "AdminFrontDeskManagementController#getUserPermissions",
            "AdminFrontDeskManagementController#updateUserPermissions", "AdminFrontDeskManagementController#getDefaultTemplate",
            "AdminFrontDeskManagementController#updateDefaultTemplate",
            "StorageLocationController#update", "StorageLocationController#list", "StorageLocationController#get",
            "StorageLocationController#updateDefaults",
            "StoreThemeController#getTheme",
            "ProductController#deleteProduct", "ProductController#setProductDiscount", "ProductController#clearProductDiscount",
            "ProductController#getProductDetails", "ProductController#getAdminProductListings");

    /** Code paths that must never reference the enforcement (plans.md 18 item 10). */
    static final List<String> NEVER_REFERENCE = List.of(
            "controller/WebhookController.java", "controller/PaymentController.java", "controller/CartController.java",
            "controller/CartItemController.java", "controller/OrderController.java", "controller/AuthController.java",
            "controller/NotificationController.java", "controller/AdminMetricController.java",
            "service/PaystackWebhookServiceImpl.java", "service/WebhookProcessorImpl.java", "service/PaymentSettlementService.java",
            "service/PaymentVerificationService.java", "service/PaystackGatewayServiceImpl.java", "service/CartServiceImpl.java",
            "service/CartItemServiceImpl.java", "service/CartPreviewService.java", "service/CheckoutPricingService.java",
            "service/OrderServiceImpl.java", "service/OrderLifecycleService.java", "service/OrderStockReservationService.java",
            "service/OrderReturnPolicy.java", "service/CouponRedemptionService.java", "service/AuthServiceImpl.java",
            "service/OtpServiceImpl.java", "service/AdminMetricServiceImpl.java", "service/NotificationInboxServiceImpl.java",
            "service/ReservedProductCleanupService.java", "service/LocationLedgerSyncImpl.java",
            "platform/service/TenantOnboardingService.java", "platform/service/PlatformTenantService.java",
            "tenancy/TenantProvisioningService.java",
            // PO 2026-11-28: platform custom-domain endpoints and the re-verification job are never plan-gated
            "platform/controller/PlatformTenantDomainController.java", "platform/domain/CustomDomainReverifier.java",
            "platform/domain/CustomDomainReverifyScheduler.java");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void everyPlanCheckInTheCodeIsAKnownCallSite_andTheirFeaturesAreExactlyTheRegistrysFeatures() throws Exception {
        Map<String, Set<String>> found = new TreeMap<>();
        for (Path p : mainSources()) {
            List<String> lines = Files.readAllLines(p);
            String cls = p.getFileName().toString().replace(".java", "");
            String method = null;
            for (String line : lines) {
                Matcher m = METHOD.matcher(line);
                if (m.find() && !line.contains(" new ") && !line.trim().startsWith("return")) {
                    method = m.group(1);
                }
                Matcher c = CALL.matcher(line);
                while (c.find()) {
                    found.computeIfAbsent(cls + "#" + method, k -> new TreeSet<>()).add(c.group(2));
                }
            }
        }
        Map<String, String> actual = new TreeMap<>();
        found.forEach((k, v) -> actual.put(k, String.join(",", v)));
        assertThat(actual).as("plan checks in src/main (add new ones to CALL_SITES and the registry)").isEqualTo(CALL_SITES);

        Set<String> codeFeatures = new TreeSet<>();
        CALL_SITES.values().forEach(v -> codeFeatures.addAll(List.of(v.split(","))));
        Set<String> registryFeatures = new TreeSet<>();
        for (Method m : annotatedHandlers()) {
            AnnotatedElementUtils.findMergedRepeatableAnnotations(m, RequiresFeature.class)
                    .forEach(r -> registryFeatures.add(r.value().name()));
        }
        assertThat(codeFeatures).isEqualTo(registryFeatures);
        Set<String> all = new TreeSet<>();
        for (PlanFeature f : PlanFeature.values()) {
            all.add(f.name());
        }
        assertThat(registryFeatures).as("every catalog key gates something").isEqualTo(all);
    }

    @Test
    void coreControllersAndNeverGatedHandlersCarryNoMarker() throws Exception {
        List<String> offenders = new ArrayList<>();
        Set<String> seenNeverGated = new TreeSet<>();
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                String id = c.getSimpleName() + "#" + m.getName();
                boolean marked = !AnnotatedElementUtils.findMergedRepeatableAnnotations(m, RequiresFeature.class).isEmpty();
                if (NEVER_GATED_HANDLERS.contains(id)) {
                    seenNeverGated.add(id);
                }
                if (marked && (CORE_CONTROLLERS.contains(c.getSimpleName()) || NEVER_GATED_HANDLERS.contains(id))) {
                    offenders.add(id);
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(seenNeverGated).as("the allow-list names real handlers").isEqualTo(new TreeSet<>(NEVER_GATED_HANDLERS));
        Set<String> names = new TreeSet<>();
        controllers().forEach(c -> names.add(c.getSimpleName()));
        assertThat(names).containsAll(CORE_CONTROLLERS);
    }

    @Test
    void scheduledAsyncListenerWebhookPaymentCheckoutOrderAuthAndLedgerCodeNeverReferencesTheEnforcement() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = Files.readString(p);
            String rel = MAIN.relativize(p).toString();
            boolean job = src.contains("@Scheduled") || src.contains("@Async") || src.contains("@TransactionalEventListener")
                    || src.contains("@EventListener") || rel.startsWith("outbox/") || rel.startsWith("task/")
                    || rel.startsWith("notification/") || rel.startsWith("handlers/") || rel.startsWith("platform/auth/");
            boolean core = NEVER_REFERENCE.contains(rel);
            boolean references = src.contains("EntitlementService") || src.contains("ThemePlanGate");
            if ((job || core) && references && !rel.equals("entitlement/EntitlementCache.java")) {
                offenders.add(rel);
            }
        }
        assertThat(offenders).isEmpty();
        for (String rel : NEVER_REFERENCE) {
            assertThat(MAIN.resolve(rel)).as("allow-list names a real file").exists();
        }
        // ledger posting from orders keeps running: the only check in BookkeepingService is the manual entry
        assertThat(CALL_SITES.keySet().stream().filter(k -> k.startsWith("BookkeepingService#")))
                .containsExactly("BookkeepingService#recordManualEntry");
    }

    /**
     * BILL-00 / BILL-01c AC7: every controller mapped under {@code /api/v1/admin/billing}, {@code /api/v1/platform/billing},
     * {@code /api/v1/platform/onboarding/card} or {@code /api/v1/dev/billing} is never gated (the member has to be able to
     * pay while restricted): no {@code @RequiresFeature} on the class or a handler, no reference to the enforcement in its
     * source, and no code of a billing package references it either (background billing code never calls a gated service).
     */
    @Test
    void billingAndCardSetupControllersAndBillingCodeAreNeverGated() throws Exception {
        List<String> offenders = new ArrayList<>();
        int seen = 0;
        for (Class<?> c : controllers()) {
            List<String> paths = new ArrayList<>();
            org.springframework.web.bind.annotation.RequestMapping base =
                    AnnotatedElementUtils.findMergedAnnotation(c, org.springframework.web.bind.annotation.RequestMapping.class);
            List<String> bases = base == null || base.path().length == 0 ? List.of("") : List.of(base.path());
            boolean billing = false;
            for (Method m : c.getDeclaredMethods()) {
                org.springframework.web.bind.annotation.RequestMapping rm =
                        AnnotatedElementUtils.findMergedAnnotation(m, org.springframework.web.bind.annotation.RequestMapping.class);
                if (rm == null) {
                    continue;
                }
                List<String> subs = rm.path().length == 0 ? List.of("") : List.of(rm.path());
                for (String b : bases) {
                    for (String sub : subs) {
                        if (NeverGatedPaths.matches((b + sub).replaceAll("/+$", ""))) {
                            billing = true;
                            if (!AnnotatedElementUtils.findMergedRepeatableAnnotations(m, RequiresFeature.class).isEmpty()) {
                                offenders.add(c.getSimpleName() + "#" + m.getName() + " carries @RequiresFeature");
                            }
                        }
                    }
                }
            }
            if (billing) {
                seen++;
                if (!AnnotatedElementUtils.findMergedRepeatableAnnotations(c, RequiresFeature.class).isEmpty()) {
                    offenders.add(c.getSimpleName() + " carries @RequiresFeature");
                }
                String src = Files.readString(mainSources().stream().filter(p -> p.getFileName().toString().equals(c.getSimpleName() + ".java"))
                        .findFirst().orElseThrow());
                if (src.contains("EntitlementService") || src.contains("ThemePlanGate") || src.contains("RequiresFeature")) {
                    offenders.add(c.getSimpleName() + " references the plan enforcement");
                }
            }
        }
        for (Path p : mainSources()) {
            String rel = MAIN.relativize(p).toString().replace('\\', '/');
            if ((rel.contains("/billing/") || rel.startsWith("billing/")) && Files.readString(p).matches("(?s).*(EntitlementService|ThemePlanGate).*")) {
                offenders.add(rel + " (billing code) references the plan enforcement");
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(seen).as("billing controllers found (grows as BILL-01.. land)").isGreaterThanOrEqualTo(0);
    }

    @Test
    void theNeverGatedPathListMatchesTheBillingFamiliesExactly() {
        assertThat(NeverGatedPaths.PREFIXES).containsExactlyInAnyOrder("/api/v1/admin/billing", "/api/v1/platform/billing",
                "/api/v1/platform/onboarding/card", "/api/v1/dev/billing");
        assertThat(NeverGatedPaths.matches("/api/v1/admin/billing")).isTrue();
        assertThat(NeverGatedPaths.matches("/api/v1/admin/billing/subscribe")).isTrue();
        assertThat(NeverGatedPaths.matches("/api/v1/platform/billing/credentials/TEST")).isTrue();
        assertThat(NeverGatedPaths.matches("/api/v1/platform/onboarding/card/setup")).isTrue();
        assertThat(NeverGatedPaths.matches("/api/v1/dev/billing/fake")).isTrue();
        assertThat(NeverGatedPaths.matches("/api/v1/admin/billingx")).as("a prefix of the string is not a path prefix").isFalse();
        assertThat(NeverGatedPaths.matches("/api/v1/admin/walk-in/quote")).isFalse();
        assertThat(NeverGatedPaths.matches("/api/v1/platform/onboarding/plans")).isFalse();
        assertThat(NeverGatedPaths.matches(null)).isFalse();
    }

    private static List<Class<?>> controllers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.perfume_budget")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (!String.valueOf(c.getProtectionDomain().getCodeSource().getLocation()).contains("test-classes")) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Method> annotatedHandlers() throws Exception {
        List<Method> out = new ArrayList<>();
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.findMergedRepeatableAnnotations(m, RequiresFeature.class).isEmpty()) {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
