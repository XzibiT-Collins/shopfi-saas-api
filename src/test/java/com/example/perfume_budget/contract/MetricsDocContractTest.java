package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.admin_dashboard.DashboardMetrics;
import com.example.perfume_budget.dto.analytics.SalesAnalyticsResponse;
import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.dto.site_visit.SiteVisitMetric;
import com.example.perfume_budget.enums.CustomerSortField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * METRICS-BE (API-RD-02 metrics part): the examples in docs/api/metrics.md parse into the real response records and
 * the documented limits are the ones in code.
 */
class MetricsDocContractTest {

    private static final Path DOC = Path.of("docs/api/metrics.md");
    private static final ObjectMapper STRICT = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static Map<String, String> examples() throws Exception {
        Matcher m = Pattern.compile("```json ([a-z-]+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(DOC));
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static JsonNode data(Map<String, String> ex, String key) throws Exception {
        return STRICT.readTree(ex.get(key)).get("data");
    }

    @Test
    void examplesMatchTheRecords() throws Exception {
        Map<String, String> ex = examples();
        assertThat(ex.keySet()).containsExactly("dashboard-response", "top-products-response", "product-response",
                "site-metric-response", "sales-analytics-response", "customers-response");

        // the dashboard DTO holds Spring projections (interfaces): check its documented keys against the record instead
        assertThat(data(ex, "dashboard-response").fieldNames()).toIterable()
                .containsExactlyInAnyOrderElementsOf(componentNames(DashboardMetrics.class));

        List<MostPurchaseProductResponse> top = STRICT.convertValue(data(ex, "top-products-response"), new TypeReference<>() {});
        MostPurchaseProductResponse one = STRICT.treeToValue(data(ex, "product-response"), MostPurchaseProductResponse.class);
        assertThat(top).hasSize(1);
        assertThat(one.conversionRate()).isEqualByComparingTo("25.00");
        assertThat(one).isEqualTo(top.get(0)); // /products/{id} is one /top-products row

        assertThat(data(ex, "site-metric-response").fieldNames()).toIterable()
                .containsExactlyInAnyOrderElementsOf(componentNames(SiteVisitMetric.class));

        JsonNode sales = data(ex, "sales-analytics-response");
        SalesAnalyticsResponse parsed = STRICT.treeToValue(sales, SalesAnalyticsResponse.class);
        assertThat(parsed.miniStats()).hasSize(1);
        assertThat(parsed.revenueBreakdown()).hasSize(1);

        PageResponse<CustomerDataResponse> customers = STRICT.convertValue(data(ex, "customers-response"), new TypeReference<>() {});
        assertThat(customers.content()).hasSize(1);
        CustomerDataResponse c = customers.content().get(0);
        assertThat(c.lastOrderDate()).isNotNull();
        assertThat(c.totalSpent()).isEqualTo("GHS 100.00");
    }

    private static List<String> componentNames(Class<?> record) {
        return java.util.Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    }

    @Test
    void documentedLimitsAndKeysExistInCode() throws Exception {
        String doc = Files.readString(DOC);
        String svc = Files.readString(Path.of("src/main/java/com/example/perfume_budget/service/AdminMetricServiceImpl.java"));
        assertThat(svc).contains("MAX_SEARCH_LENGTH = 100").contains("MAX_PAGE_SIZE = 50").contains("DEFAULT_PAGE_SIZE = 20");
        assertThat(doc).contains("at most **100** characters").contains("capped at **50**").contains("unpaged requests use 20");
        for (String key : new String[]{"orders", "totalSpent", "lastOrder"}) {
            assertThat(doc).contains("`" + key + "`");
            assertThat(CustomerSortField.parse(key)).isNotNull();
        }
        // every route of the doc table exists on the controller
        String controller = Files.readString(Path.of("src/main/java/com/example/perfume_budget/controller/AdminMetricController.java"));
        for (String route : new String[]{"/dashboard-metric", "/top-products", "/products/{productId}", "/site-metric", "/sales-analytics", "/customers"}) {
            assertThat(doc).contains("`" + route);
            assertThat(controller).contains("\"" + route + "\"");
        }
        // sales-analytics defaults and enums
        for (String v : new String[]{"ALL", "ONLINE", "WALK_IN", "DAY", "WEEK", "MONTH", "YEAR"}) assertThat(doc).contains(v);
    }
}
