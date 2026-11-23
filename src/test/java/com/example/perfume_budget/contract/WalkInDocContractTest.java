package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.walk_in.request.CustomerSearchRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInQuoteRequest;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInQuoteResponse;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WALKIN-BE (API-RD-02): every JSON example in docs/api/walk-in.md parses into the real request/response records with
 * unknown properties REJECTED, and the documented error codes exist in code.
 */
class WalkInDocContractTest {

    private static final Path DOC = Path.of("docs/api/walk-in.md");
    private static final ObjectMapper STRICT = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static Map<String, String> examples() throws Exception {
        Matcher m = Pattern.compile("```json ([a-z-]+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(DOC));
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    @Test
    void examplesMatchTheRecords() throws Exception {
        Map<String, String> ex = examples();
        assertThat(ex.keySet()).containsExactly("quote-request", "quote-response", "order-request", "order-response", "customer-search-request");

        WalkInQuoteRequest qr = STRICT.readValue(ex.get("quote-request"), WalkInQuoteRequest.class);
        assertThat(qr.items()).hasSize(1);
        WalkInQuoteResponse q = STRICT.treeToValue(STRICT.readTree(ex.get("quote-response")).get("data"), WalkInQuoteResponse.class);
        assertThat(q.totalAmount()).isEqualTo("GHS 99.00");
        assertThat(q.discountValue()).isEqualTo("10.00%");
        assertThat(q.taxes()).hasSize(1);

        WalkInOrderRequest or = STRICT.readValue(ex.get("order-request"), WalkInOrderRequest.class);
        assertThat(or.paymentMethod()).isNotNull();
        JsonNode od = STRICT.readTree(ex.get("order-response")).get("data");
        WalkInOrderResponse o = STRICT.treeToValue(od, WalkInOrderResponse.class);
        assertThat(o.idempotentReplay()).isFalse();
        assertThat(o.totalAmount()).isEqualTo(q.totalAmount());

        assertThat(STRICT.readValue(ex.get("customer-search-request"), CustomerSearchRequest.class).query()).isNotBlank();
    }

    @Test
    void documentedCodesExistInCode() throws Exception {
        String doc = Files.readString(DOC);
        String svc = Files.readString(Path.of("src/main/java/com/example/perfume_budget/service/WalkInOrderServiceImpl.java"));
        String handler = Files.readString(Path.of("src/main/java/com/example/perfume_budget/exception/handlers/GlobalExceptionHandler.java"));
        for (String code : new String[]{"IDEMPOTENCY_KEY_INVALID", "IDEMPOTENCY_KEY_REUSED"}) {
            assertThat(doc).contains(code);
            assertThat(svc).contains("\"" + code + "\"");
        }
        assertThat(doc).contains("ACCOUNTING_ERROR");
        assertThat(handler).contains("\"ACCOUNTING_ERROR\"");
    }
}
