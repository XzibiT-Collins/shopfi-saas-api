package com.example.perfume_budget.platform.billing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01 AC8 / billing.md 21 item 2: no billing code path reads or writes a shop's own payment gateway configuration.
 * Static test on the dependencies of {@code platform.billing..}: neither the sources (imports and identifiers) nor the
 * compiled classes (constant pool: every referenced class name is there as UTF-8) mention
 * {@code TenantPaymentGatewayProvider}, {@code PaymentGatewayConfig*}, {@code PaymentSettings*} or {@code PaystackGatewayServiceImpl}.
 */
class BillingPackageIsolationTest {

    private static final List<String> FORBIDDEN = List.of("TenantPaymentGatewayProvider", "PaymentGatewayConfig", "PaymentSettings",
            "PaystackGatewayServiceImpl", "PaymentGatewayAPIService", "WebhookProcessor");
    private static final Path SRC = Path.of("src/main/java/com/example/perfume_budget/platform/billing");
    private static final Path CLS = Path.of("target/classes/com/example/perfume_budget/platform/billing");

    @Test
    void billingSourcesDoNotDependOnAShopsGatewayConfiguration() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f);
                for (String bad : FORBIDDEN) {
                    if (text.contains(bad)) {
                        offenders.add(SRC.relativize(f) + " mentions " + bad);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void billingClassesDoNotReferenceThem_andTheScanSeesTheBillingClasses() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(CLS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                scanned++;
                String bytes = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                for (String bad : FORBIDDEN) {
                    if (bytes.contains(bad)) {
                        offenders.add(CLS.relativize(f) + " references " + bad);
                    }
                }
            }
        }
        assertThat(scanned).as("compiled billing classes scanned").isGreaterThan(30);
        assertThat(offenders).isEmpty();
    }

    @Test
    void theShopGatewayCodeDoesNotDependOnBilling() throws IOException {
        // the reverse direction: a shop's gateway classes never import platform.billing
        Path service = Path.of("src/main/java/com/example/perfume_budget/service");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(service)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains("platform.billing")) {
                    offenders.add(f.toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
