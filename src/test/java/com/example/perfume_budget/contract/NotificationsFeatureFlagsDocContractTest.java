package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.feature_flag.request.FeatureFlagUpdateRequest;
import com.example.perfume_budget.dto.feature_flag.response.FeatureFlagResponse;
import com.example.perfume_budget.dto.notification.NotificationResponse;
import com.example.perfume_budget.dto.notification.UnreadNotificationCountResponse;
import com.example.perfume_budget.dto.ws.WebSocketNotificationBody;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-RD-02 (notifications, feature flags): the named JSON examples of docs/api/notifications.md and feature-flags.md
 * parse into the real records with unknown properties REJECTED, and the rules the docs state exist in code.
 */
class NotificationsFeatureFlagsDocContractTest {

    private static final ObjectMapper STRICT = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String SRC = "src/main/java/com/example/perfume_budget/";

    private static Map<String, String> examples(String doc) throws Exception {
        Matcher m = Pattern.compile("```json ([a-z-]+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(Path.of("docs/api/" + doc)));
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void notificationExamplesMatchTheRecords() throws Exception {
        Map<String, String> ex = examples("notifications.md");
        assertThat(ex.keySet()).containsExactly("notification-response", "unread-count-response");

        JsonNode page = STRICT.readTree(ex.get("notification-response")).get("data");
        PageResponse<NotificationResponse> parsed = STRICT.convertValue(page, new TypeReference<>() {});
        assertThat(parsed.content()).hasSize(1);
        NotificationResponse n = parsed.content().get(0);
        assertThat(n.type()).isEqualTo("PAYMENT_SUCCESS");
        assertThat(n.read()).isFalse();
        assertThat(n.readAt()).isNull();
        assertThat(n.recipientId()).isNotEqualTo(n.notificationId());

        assertThat(STRICT.readValue(ex.get("unread-count-response"), new TypeReference<Map<String, Object>>() {})).containsKey("data");
        UnreadNotificationCountResponse c = STRICT.treeToValue(STRICT.readTree(ex.get("unread-count-response")).get("data"),
                UnreadNotificationCountResponse.class);
        assertThat(c.unreadCount()).isEqualTo(3L);

        // the websocket body of the docs ("exactly the fields of a notification row") really is the same field list
        assertThat(names(WebSocketNotificationBody.class)).containsExactlyElementsOf(names(NotificationResponse.class));
    }

    private static List<String> names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void notificationRulesExistInCode() throws Exception {
        String doc = read("docs/api/notifications.md");
        String controller = read(SRC + "controller/NotificationController.java");
        String inbox = read(SRC + "service/NotificationInboxServiceImpl.java");
        String ws = read(SRC + "config/ws/NotificationService.java");
        String repoSrc = read(SRC + "repository/NotificationRepository.java");

        // role gate and every route of the table
        assertThat(controller).contains("@PreAuthorize(\"hasAnyRole('ADMIN','FRONT_DESK')\")")
                .contains("@RequestMapping(\"/api/v1/admin/notifications\")")
                .contains("@GetMapping(\"/unread-count\")").contains("@PatchMapping(\"/{recipientId}/read\")")
                .contains("@PatchMapping(\"/read-all\")").contains("ResponseEntity.noContent()");
        assertThat(doc).contains("`/unread-count`").contains("`/{recipientId}/read`").contains("`/read-all`");
        // personal inbox: rows are looked up by (id, current user), and the not-found text is the documented one
        assertThat(inbox).contains("findByIdAndUser(recipientId, currentUser)").contains("\"Notification not found.\"")
                .contains("findByUserOrderByCreatedAtDesc(currentUser, pageable)");
        assertThat(doc).contains("`404 Notification not found.`").contains("newest first");
        // read-once semantics: an already read row keeps its readAt
        assertThat(inbox).contains("if (!recipient.isRead())");
        assertThat(doc).contains("`readAt` keeps its first value");
        // the two types and their references, and the throttle
        assertThat(repoSrc).contains("'PAYMENT_SUCCESS'").contains("'ORDER'");
        assertThat(ws).contains("\"SHOP_FLOOR_RESTOCK\"").contains("\"PRODUCT\"")
                .contains("existsByNotificationTypeAndNotificationReferenceIdAndIsReadFalse")
                .contains("List.of(UserRole.ADMIN, UserRole.FRONT_DESK)").contains("/queue/notifications");
        assertThat(doc).contains("`PAYMENT_SUCCESS`").contains("`SHOP_FLOOR_RESTOCK`").contains("/user/queue/notifications")
                .contains("At most one\n  **unread** alert per product");
        // documented title / message text of the payment event
        assertThat(ws).contains("\"Payment received\"").contains("has been successfully paid.");
        assertThat(doc).contains("\"title\": \"Payment received\"").contains("has been successfully paid.");
        // column lengths the docs state (title 128, message 512)
        String ddl = read("src/main/resources/db/migrations/tenant/V1__initial_create_tables.sql");
        assertThat(ddl).contains("type           VARCHAR(64)").contains("title          VARCHAR(128)").contains("message        VARCHAR(512)");
        assertThat(doc).contains("at most 128 / 512 characters");
        // page cap named by the docs
        assertThat(read("src/main/resources/application-prod.properties")).contains("spring.data.web.pageable.max-page-size=50");
        assertThat(doc).contains("cap it at **50**");
    }

    @Test
    void featureFlagExamplesMatchTheRecords() throws Exception {
        Map<String, String> ex = examples("feature-flags.md");
        assertThat(ex.keySet()).containsExactly("feature-flag-response", "feature-flag-request");
        FeatureFlagResponse r = STRICT.treeToValue(STRICT.readTree(ex.get("feature-flag-response")).get("data"), FeatureFlagResponse.class);
        assertThat(r.featureKey()).isEqualTo(FeatureFlagKey.LOGIN_OTP);
        assertThat(r.description()).isEqualTo(FeatureFlagKey.LOGIN_OTP.getDescription());
        FeatureFlagUpdateRequest req = STRICT.readValue(ex.get("feature-flag-request"), FeatureFlagUpdateRequest.class);
        assertThat(req.adminEnabled()).isTrue();
        assertThat(req.frontDeskEnabled()).isFalse();
    }

    @Test
    void featureFlagRulesExistInCode() throws Exception {
        String doc = read("docs/api/feature-flags.md");
        String controller = read(SRC + "controller/FeatureFlagController.java");
        String svc = read(SRC + "service/FeatureFlagServiceImpl.java");
        // one key today: the doc table must list every key and nothing else
        assertThat(FeatureFlagKey.values()).hasSize(1);
        for (FeatureFlagKey k : FeatureFlagKey.values()) assertThat(doc).contains("`" + k.name() + "`").contains(k.getDescription());
        // ADMIN only on all three handlers
        assertThat(controller.split("@PreAuthorize\\(\"hasRole\\('ADMIN'\\)\"\\)", -1)).hasSize(4);
        // all three audiences are written by a PATCH, and an unset audience reads as off
        assertThat(svc).contains("FeatureAudience.ADMIN, request.adminEnabled()").contains("FeatureAudience.FRONT_DESK, request.frontDeskEnabled()")
                .contains("FeatureAudience.CUSTOMER, request.customerEnabled()").contains(".orElse(false)");
        assertThat(doc).contains("reads as **off**").contains("All three booleans are **required**");
        assertThat(read(SRC + "dto/feature_flag/request/FeatureFlagUpdateRequest.java").split("@NotNull", -1)).hasSize(4);
        // an unknown key in the path is a type mismatch, answered 400 "Invalid parameter type"
        assertThat(controller).contains("@PathVariable FeatureFlagKey featureKey");
        assertThat(read(SRC + "exception/handlers/GlobalExceptionHandler.java"))
                .contains("handleMethodArgumentTypeMismatchException").contains("HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(\"Invalid parameter type\"))");
        assertThat(doc).contains("| 400 | an unknown `featureKey` (path) | `Invalid parameter type` |");
        // the login flow the table names
        assertThat(read(SRC + "service/AuthServiceImpl.java")).contains("featureFlagService.isEnabled(FeatureFlagKey.LOGIN_OTP, user.getRoles())");
    }
}
