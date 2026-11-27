package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.dto.PlatformAuditPage;
import com.example.perfume_budget.platform.dto.PlatformTenantSummary;
import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantAuditPhase;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** PLAT-04: the detail and audit examples in docs/api/platform-tenants.md parse into the real DTOs. */
class PlatformReadContractTest {

    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static Map<String, String> examples() throws Exception {
        Matcher m = Pattern.compile("```json plat04:([a-z0-9-]+)\\n(.*?)```", Pattern.DOTALL)
                .matcher(Files.readString(Path.of("docs/api/platform-tenants.md")));
        Map<String, String> out = new HashMap<>();
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    void detailAndAuditExamplesArePresent() throws Exception {
        assertThat(examples().keySet()).containsExactlyInAnyOrder("detail-response", "audit-response");
    }

    @Test
    void detailExampleParsesIntoTheRealDto() throws Exception {
        String json = examples().get("detail-response");
        CustomApiResponse<PlatformTenantSummary> r = MAPPER.readValue(json, new TypeReference<>() { });
        assertThat(r.data().slug()).isEqualTo("acme");
        PlatformTenantsContractTest.assertPlanFields(MAPPER.readTree(json).get("data")); // PLAN-01 AC11
        assertThat(json).doesNotContain("schemaName").doesNotContain("webhookToken").doesNotContain("password");
    }

    @Test
    void auditExampleParsesIntoTheRealDto_withExactKeysAndRealEnums() throws Exception {
        String json = examples().get("audit-response");
        CustomApiResponse<PlatformAuditPage> r = MAPPER.readValue(json, new TypeReference<>() { });
        assertThat(r.data().items()).hasSize(2);
        assertThat(r.data().items().get(0).id()).isGreaterThan(r.data().items().get(1).id()); // newest first
        r.data().items().forEach(i -> {
            TenantAction.valueOf(i.action());
            TenantAuditPhase.valueOf(i.phase());
            TenantStatus.valueOf(i.previousStatus());
            TenantStatus.valueOf(i.newStatus());
            assertThat(i.occurredAt().getOffset().getTotalSeconds()).isZero();
        });
        JsonNode row = MAPPER.readTree(json).get("data").get("items").get(0);
        Set<String> keys = new HashSet<>();
        row.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isEqualTo(Set.of("id", "tenantSlug", "action", "phase", "actorEmail", "previousStatus", "newStatus", "occurredAt"));
        assertThat(json).doesNotContain("password").doesNotContain("hash").doesNotContain("token").doesNotContain("schema");
    }
}
