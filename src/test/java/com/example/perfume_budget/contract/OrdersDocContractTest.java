package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.order.OrderResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** ORD-01: the JSON example and the endpoints/codes in docs/api/orders.md match the real record and code. */
class OrdersDocContractTest {

    private static final Path DOC = Path.of("docs/api/orders.md");
    private static final ObjectMapper STRICT = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    void orderResponseExampleParsesStrictlyAndCoversEveryField() throws Exception {
        String md = Files.readString(DOC);
        var m = java.util.regex.Pattern.compile("```json order-response-admin\\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(md);
        assertThat(m.find()).isTrue();
        JsonNode data = STRICT.readTree(m.group(1)).get("data");
        OrderResponse r = STRICT.treeToValue(data, OrderResponse.class);
        assertThat(r.version()).isEqualTo(4L);
        assertThat(r.account().email()).isEqualTo("ama@example.com");
        assertThat(r.orderedAt().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(r.taxes().orderTaxes().get(0).taxRatePercent()).isEqualByComparingTo("10.00");
        String[] expected = Arrays.stream(OrderResponse.class.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
        java.util.List<String> actual = new java.util.ArrayList<>();
        data.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }

    @Test
    void documentedRoutesAndCodesExistInTheCode() throws Exception {
        String md = Files.readString(DOC);
        StringBuilder src = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) src.append(Files.readString(f));
        }
        for (String ep : new String[]{"/api/v1/order/update-order-status/{orderNumber}", "/api/v1/order/{orderNumber}/refund-acknowledgement", "/api/v1/order/all",
                "/api/v1/order/{orderNumber}/return-received", "/api/v1/order/{orderNumber}/return", "/api/v1/order/{orderNumber}/audit-events"}) {
            assertThat(md).contains(ep);
        }
        assertThat(src.toString()).contains("\"/{orderNumber}/refund-acknowledgement\"").contains("\"/update-order-status/{orderNumber}\"")
                .contains("\"/{orderNumber}/return-received\"").contains("\"/{orderNumber}/return\"").contains("\"/{orderNumber}/audit-events\"");
        for (String code : new String[]{"ORDER_FINAL_STATE", "ORDER_TRANSITION_NOT_ALLOWED", "ORDER_NOT_PAID", "VERSION_MISMATCH", "INVALID_IF_MATCH",
                "INVALID_DATE_FILTER", "INVALID_DATE_RANGE", "INVALID_SEARCH", "INVALID_PAGE", "INVALID_SORT", "ORDER_NOT_FOUND",
                "ORDER_VERSION_CONFLICT", "REFUND_NOT_REQUIRED", "RETURN_NOT_APPLICABLE", "NOTHING_TO_RESTOCK", "INSUFFICIENT_CASH", "ORDER_NOT_RETURNABLE", "RETURN_WINDOW_EXPIRED"}) {
            assertThat(md).as("doc mentions " + code).contains("`" + code + "`");
            assertThat(src.toString()).as("code defines " + code).contains("\"" + code + "\"");
        }
    }
}
