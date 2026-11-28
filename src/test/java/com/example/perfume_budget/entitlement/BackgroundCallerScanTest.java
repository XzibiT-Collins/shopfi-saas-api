package com.example.perfume_budget.entitlement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01c AC6: every background entry point of the production code (the scheduled jobs, async handlers, event listeners
 * and the programmatic schedulers) carries an explicit {@code @NotPlanGated(reason)} decision; none of them reaches a
 * service method that calls the plan enforcement. A new background caller of a gated service, or an unclassified one,
 * fails the build. The scan itself is proved on synthetic sources (it must flag each mistake it exists for).
 */
class BackgroundCallerScanTest {

    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.put(p.getFileName().toString().replace(".java", ""), Files.readString(p));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ production code

    @Test
    void everyBackgroundEntryPointIsClassified_andNoneReachesAPlanCheck() throws Exception {
        BackgroundCallerScan scan = new BackgroundCallerScan(mainSources());
        assertThat(scan.findings()).isEmpty();
    }

    @Test
    void theScanSeesTheKnownBackgroundPathsAndTheKnownGates() throws Exception {
        BackgroundCallerScan scan = new BackgroundCallerScan(mainSources());
        Map<String, String> kinds = scan.classifiedKinds();
        assertThat(kinds.keySet()).contains(
                "ReservedProductCleanupTask#cleanupExpiredReservations", "DiscountCacheEvictionTask#evictProductCaches",
                "StompSessionRevalidator#revalidate", "SuccessfulOrderHandler#handleOrderCreationEvent",
                "SuccessfulOrderHandler#handlePaymentSuccess", "ProductViewCountHandler#countProductViews",
                "ShopFloorStockHandler#handleShopFloorStockChange", "OTPEmailSendHandler#handleLoginOtpEvent",
                "ForgotPasswordHandler#handlePasswordResetEvent", "OrderStatusHandler#handleOrderStatusChangeEvent",
                "EntitlementCache#onPlansChanged", "EntitlementCache#onTenantSubscriptionChanged",
                "CustomDomainReverifyScheduler#round", "OutboxDrainScheduler#start");
        assertThat(kinds.values()).as("today every background path is explicitly NOT gated").containsOnly("NotPlanGated");
        assertThat(kinds).hasSizeGreaterThanOrEqualTo(20);
        assertThat(scan.gatedNodes().stream().map(Object::toString)).contains("TaxServiceImpl#createTax",
                "StorageLocationServiceImpl#create", "CustomDomainService#requirePlan", "ThemePlanGate#check");
    }

    @Test
    void theDocumentedDecisionIsInPlansMd() throws Exception {
        String doc = Files.readString(Path.of("docs/api/plans.md"));
        assertThat(doc).contains("@NotPlanGated").contains("@PlanGatedBackground").contains("BackgroundCallerScanTest");
    }

    // ------------------------------------------------------------------------------------------ the scan itself

    private static final String GATED_SERVICE = """
            package x;
            public class ThingServiceImpl implements ThingService {
                private final EntitlementService entitlementService;
                public void create() {
                    entitlementService.requireLimitSlot(PlanFeature.MAX_PRODUCTS);
                }
                public void harmless() {
                }
            }
            """;

    private static String job(String annotation, String classification, String call) {
        return """
                package x;
                public class NightlyJob {
                    private final ThingService things;
                    %s
                    %s
                    public void run() {
                        things.%s();
                    }
                }
                """.formatted(classification, annotation, call);
    }

    @Test
    void scan_flagsAnUnclassifiedScheduledMethod() {
        var scan = new BackgroundCallerScan(Map.of("ThingServiceImpl", GATED_SERVICE, "NightlyJob", job("@Scheduled(fixedDelay = 5)", "", "harmless")));
        assertThat(scan.findings()).singleElement().asString().contains("NightlyJob#run").contains("without @NotPlanGated");
    }

    @Test
    void scan_flagsEachKindOfBackgroundEntry() {
        for (String ann : List.of("@Scheduled(fixedDelay = 5)", "@Async", "@EventListener", "@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)")) {
            var scan = new BackgroundCallerScan(Map.of("ThingServiceImpl", GATED_SERVICE, "NightlyJob", job(ann, "", "harmless")));
            assertThat(scan.findings()).as(ann).hasSize(1);
        }
    }

    @Test
    void scan_acceptsAClassifiedJobThatDoesNotReachAGate() {
        var scan = new BackgroundCallerScan(Map.of("ThingServiceImpl", GATED_SERVICE,
                "NightlyJob", job("@Scheduled(fixedDelay = 5)", "@NotPlanGated(reason = \"housekeeping\")", "harmless")));
        assertThat(scan.findings()).isEmpty();
    }

    @Test
    void scan_flagsANotPlanGatedJobThatCallsAGatedServiceThroughItsInterface() {
        var scan = new BackgroundCallerScan(Map.of("ThingServiceImpl", GATED_SERVICE,
                "NightlyJob", job("@Scheduled(fixedDelay = 5)", "@NotPlanGated(reason = \"housekeeping\")", "create")));
        assertThat(scan.findings()).singleElement().asString().contains("NightlyJob#run is @NotPlanGated but reaches a plan check")
                .contains("ThingServiceImpl#create");
    }

    @Test
    void scan_followsTwoHops_andAnExplicitPlanGatedBackgroundDecisionIsAccepted() {
        String hop = """
                package x;
                public class MidService {
                    private final ThingService things;
                    public void relay() {
                        things.create();
                    }
                }
                """;
        String twoHop = """
                package x;
                public class NightlyJob {
                    private final MidService mid;
                    %s
                    @Scheduled(fixedDelay = 5)
                    public void run() {
                        mid.relay();
                    }
                }
                """;
        var bad = new BackgroundCallerScan(Map.of("ThingServiceImpl", GATED_SERVICE, "MidService", hop,
                "NightlyJob", twoHop.formatted("@NotPlanGated(reason = \"x\")")));
        assertThat(bad.findings()).singleElement().asString().contains("MidService#relay").contains("ThingServiceImpl#create");
        var ok = new BackgroundCallerScan(Map.of("ThingServiceImpl", GATED_SERVICE, "MidService", hop,
                "NightlyJob", twoHop.formatted("@PlanGatedBackground(reason = \"starts new gated work\")")));
        assertThat(ok.findings()).isEmpty();
    }

    @Test
    void scan_flagsABlankReason_andAProgrammaticSchedulerWithoutClassification() {
        var blank = new BackgroundCallerScan(Map.of("NightlyJob", job("@Async", "@NotPlanGated(reason = \"\")", "harmless"),
                "ThingServiceImpl", GATED_SERVICE));
        assertThat(blank.findings()).singleElement().asString().contains("without a reason");
        String programmatic = """
                package x;
                public class TimerThing {
                    private ScheduledExecutorService executor;
                    public void start() {
                        executor.scheduleWithFixedDelay(this::round, 1, 1, TimeUnit.SECONDS);
                    }
                    void round() {
                    }
                }
                """;
        var scan = new BackgroundCallerScan(Map.of("TimerThing", programmatic));
        assertThat(scan.findings()).hasSize(2).allMatch(f -> f.startsWith("TimerThing#") && f.contains("without @NotPlanGated"));
        var classified = new BackgroundCallerScan(Map.of("TimerThing",
                programmatic.replace("public class TimerThing", "@NotPlanGated(reason = \"timer\")\npublic class TimerThing")));
        assertThat(classified.findings()).isEmpty();
    }

    /** Security lows 3: other ways of starting background work are entry points too (before billing workers land). */
    @Test
    void scan_treatsLifecycleThreadsExecutorsAndAsyncFuturesAsBackgroundEntryPoints() {
        List<String> bodies = List.of(
                "private final ExecutorService pool;\n    public void go() {\n        pool.submit(this::work);\n    }\n    void work() {\n    }",
                "public void go() {\n        java.util.concurrent.CompletableFuture.runAsync(this::work);\n    }\n    void work() {\n    }",
                "public void go() {\n        CompletableFuture.supplyAsync(() -> 1);\n    }",
                "public void go() {\n        new Thread(this::work).start();\n    }\n    void work() {\n    }",
                "public void go() {\n        Executors.newFixedThreadPool(2);\n    }",
                "public void go() {\n        Thread.ofVirtual().start(() -> { });\n    }");
        for (String b : bodies) {
            var scan = new BackgroundCallerScan(Map.of("Worker", "package x;\npublic class Worker {\n    " + b + "\n}\n"));
            assertThat(scan.findings()).as(b).isNotEmpty().allMatch(f -> f.startsWith("Worker#") && f.contains("without @NotPlanGated"));
        }
        String lifecycle = "package x;\npublic class Boot implements SmartLifecycle {\n    public void start() {\n    }\n    public void stop() {\n    }\n}\n";
        assertThat(new BackgroundCallerScan(Map.of("Boot", lifecycle)).findings()).hasSize(2);
        assertThat(new BackgroundCallerScan(Map.of("Boot", lifecycle.replace("public class Boot",
                "@NotPlanGated(reason = \"lifecycle\")\npublic class Boot"))).findings()).isEmpty();
    }
}
