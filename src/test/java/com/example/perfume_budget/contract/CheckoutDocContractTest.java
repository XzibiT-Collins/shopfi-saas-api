package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.checkout.CartPreviewRequest;
import com.example.perfume_budget.dto.checkout.CartPreviewResponse;
import com.example.perfume_budget.dto.checkout.CheckoutResponse;
import com.example.perfume_budget.dto.checkout.PaymentVerificationResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAY-01 AC6: every JSON example in docs/api/checkout.md parses into the real response/request records with unknown
 * properties REJECTED (so a renamed/removed/added field fails here), and the documented endpoints/codes exist in code.
 */
class CheckoutDocContractTest {

    private static final Path DOC = Path.of("docs/api/checkout.md");
    private static final ObjectMapper STRICT = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static Map<String, String> examples() throws Exception {
        String md = Files.readString(DOC);
        Matcher m = Pattern.compile("```json ([a-z-]+)\\n(.*?)```", Pattern.DOTALL).matcher(md);
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    @Test
    void allExamplesAreValidJsonAndMatchTheRecords() throws Exception {
        Map<String, String> ex = examples();
        assertThat(ex.keySet()).containsExactly("checkout-response", "cart-preview-request", "cart-preview-response", "payment-verify-response");

        JsonNode co = STRICT.readTree(ex.get("checkout-response"));
        CheckoutResponse checkout = STRICT.treeToValue(co.get("data"), CheckoutResponse.class);
        assertThat(checkout.orderNumber()).isNotBlank();
        assertThat(checkout.data().authorizationUrl()).startsWith("https://checkout.paystack.com/");
        assertThat(checkout.data().reference()).isNotBlank();
        assertThat(checkout.paymentStatus()).isNotNull();

        assertThat(STRICT.readValue(ex.get("cart-preview-request"), CartPreviewRequest.class).couponCode()).isEqualTo("SAVE10");

        CartPreviewResponse preview = STRICT.treeToValue(STRICT.readTree(ex.get("cart-preview-response")).get("data"), CartPreviewResponse.class);
        assertThat(preview.totalAmount()).isEqualTo(preview.taxes().totalAmountAfterTax());
        assertThat(preview.coupon().valid()).isTrue();

        PaymentVerificationResponse verify = STRICT.treeToValue(STRICT.readTree(ex.get("payment-verify-response")).get("data"), PaymentVerificationResponse.class);
        assertThat(verify.settled()).isTrue();
        assertThat(verify.paymentStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void everyDocumentedExampleFieldExistsAndEveryRecordFieldIsDocumented() throws Exception {
        Map<String, String> ex = examples();
        assertThat(fields(STRICT.readTree(ex.get("checkout-response")).get("data")))
                .containsExactlyInAnyOrder(recordFields(CheckoutResponse.class));
        assertThat(fields(STRICT.readTree(ex.get("payment-verify-response")).get("data")))
                .containsExactlyInAnyOrder(recordFields(PaymentVerificationResponse.class));
        assertThat(fields(STRICT.readTree(ex.get("cart-preview-response")).get("data")))
                .containsExactlyInAnyOrder(recordFields(CartPreviewResponse.class));
    }

    @Test
    void documentedEndpointsAndErrorCodesExistInTheCode() throws Exception {
        String md = Files.readString(DOC);
        String src = readAll(Path.of("src/main/java"));
        for (String ep : new String[]{"/api/v1/cart/checkout", "/api/v1/cart/preview", "/api/v1/payment/verify"}) {
            assertThat(md).contains(ep);
        }
        assertThat(md).contains("/api/v1/order/{orderNumber}/cancel");
        assertThat(src).contains("@PostMapping(\"/{orderNumber}/cancel\")").contains("@RequestMapping(\"/api/v1/order\")")
                .contains("@PostMapping(\"/preview\")").contains("@PostMapping(\"/checkout\")").contains("@GetMapping(\"/verify\")");
        Matcher codes = Pattern.compile("\\| \\d{3} \\| `([A-Z_]+)` \\|").matcher(md);
        int n = 0;
        while (codes.find()) {
            n++;
            assertThat(src).as("code %s used in main sources", codes.group(1)).contains("\"" + codes.group(1) + "\"");
        }
        assertThat(n).isGreaterThanOrEqualTo(9);
        assertThat(md).contains("Idempotency-Key").contains("callback_url").contains("charge.failed").contains("charge.success");
    }

    private static String[] fields(JsonNode n) {
        java.util.List<String> l = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(l::add);
        return l.toArray(String[]::new);
    }

    private static String[] recordFields(Class<?> rec) {
        return Stream.of(rec.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new);
    }

    private static String readAll(Path root) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) sb.append(Files.readString(p));
        }
        return sb.toString();
    }
}
