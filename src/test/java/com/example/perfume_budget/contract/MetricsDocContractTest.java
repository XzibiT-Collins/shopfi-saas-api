package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.admin_dashboard.DashboardMetrics;
import com.example.perfume_budget.dto.analytics.SalesAnalyticsResponse;
import com.example.perfume_budget.dto.coupon.response.CouponMetricResponse;
import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.dto.customer.CustomerFullDetailsResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.dto.site_visit.SiteVisitMetric;
import com.example.perfume_budget.enums.CustomerSortField;
import com.example.perfume_budget.projection.LowStockProduct;
import com.example.perfume_budget.projection.TopCustomer;
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
                "site-metric-response", "sales-analytics-response", "customers-response", "customer-detail-response",
                "customer-orders-response", "coupons-response", "low-stock-response", "top-customers-response");

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

    /** API-RD-02 remainder: the five routes documented at the end of metrics.md. */
    @Test
    void remainderExamplesMatchTheRecordsAndProjections() throws Exception {
        Map<String, String> ex = examples();

        CustomerFullDetailsResponse detail = STRICT.treeToValue(data(ex, "customer-detail-response"), CustomerFullDetailsResponse.class);
        assertThat(detail.addresses()).hasSize(1);
        assertThat(detail.addresses().get(0).isDefault()).isTrue();
        assertThat(detail.totalSpent()).isEqualTo("GHS 100.00");
        assertThat(detail.orderCount()).isEqualTo(2L);

        PageResponse<OrderListResponse> orders = STRICT.convertValue(data(ex, "customer-orders-response"), new TypeReference<>() {});
        assertThat(orders.content()).hasSize(1);
        assertThat(orders.content().get(0).orderedAt()).isNotNull();
        assertThat(orders.number()).isEqualTo(1);

        CouponMetricResponse coupons = STRICT.treeToValue(data(ex, "coupons-response"), CouponMetricResponse.class);
        assertThat(coupons.totalCreated()).isEqualTo(coupons.coupons().size());
        assertThat(coupons.totalDiscountGiven()).isEqualTo("GHS 40.00");

        // projections are interfaces: the documented keys are exactly their getters
        JsonNode low = data(ex, "low-stock-response").get(0);
        assertThat(low.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(getterNames(LowStockProduct.class));
        JsonNode top = data(ex, "top-customers-response").get(0);
        assertThat(top.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(getterNames(TopCustomer.class));
        assertThat(top.get("totalAmountSpent").isNumber()).isTrue(); // a number, not a "GHS .." string
    }

    private static List<String> getterNames(Class<?> projection) {
        return java.util.Arrays.stream(projection.getMethods())
                .filter(m -> m.getName().startsWith("get"))
                .map(m -> Character.toLowerCase(m.getName().charAt(3)) + m.getName().substring(4)).toList();
    }

    @Test
    void remainderLimitsAndRoutesExistInCode() throws Exception {
        String doc = Files.readString(DOC);
        String svc = Files.readString(Path.of("src/main/java/com/example/perfume_budget/service/AdminMetricServiceImpl.java"));
        String repo = Files.readString(Path.of("src/main/java/com/example/perfume_budget/repository/ProductRepository.java"));
        String controller = Files.readString(Path.of("src/main/java/com/example/perfume_budget/controller/AdminMetricController.java"));
        for (String route : new String[]{"/customers/{userId}", "/customers/{userId}/orders", "/coupons", "/low-stock-products", "/top-customers"}) {
            assertThat(doc).as(route).contains("`" + route);
            assertThat(controller).as(route).contains("\"" + route + "\"");
        }
        // "at most 10" rows for both lists
        assertThat(svc).contains("Pageable pageable = Pageable.ofSize(10);\n        return productRepository.findLowStockProducts(pageable)")
                .contains("orderRepository.findTopCustomers(PaymentStatus.COMPLETED, Pageable.ofSize(10))");
        assertThat(doc).contains("At most **10** rows, the products").contains("At most **10** rows, ordered by order count");
        assertThat(repo).contains("p.stockQuantity <= p.lowStockThreshold").contains("ORDER BY p.stockQuantity ASC");
        // the 404 text and the cancelled / returned exclusion the doc states
        assertThat(svc).contains("new ResourceNotFoundException(\"User not found\")");
        assertThat(doc).contains("`404` `User not found`");
        assertThat(svc).contains("OrderProcessingStatus.CANCELLED && o.getDeliveryStatus() != OrderProcessingStatus.RETURNED");
        // page cap the doc names is the profile property
        for (String profile : new String[]{"dev", "prod"}) {
            assertThat(Files.readString(Path.of("src/main/resources/application-" + profile + ".properties")))
                    .contains("spring.data.web.pageable.max-page-size=50");
        }
        assertThat(doc).contains("cap it at **50**");
        // staff ids are not filtered out of the detail lookup (documented as unchanged)
        assertThat(svc).contains("findWithDeliveryAddressesById(userId)");
        assertThat(doc).contains("Not limited to customers");
        assertThat(controller).contains("@PreAuthorize(\"hasRole('ADMIN')\")");
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
