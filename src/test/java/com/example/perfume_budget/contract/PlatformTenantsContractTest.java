package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.dto.PlatformTenantPage;
import com.example.perfume_budget.platform.dto.TenantActionResponse;
import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.service.TenantStateMachine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAT-02: docs/api/platform-tenants.md is the contract UI-09 builds against. Its JSON examples must parse into the real
 * DTOs (unknown or missing fields fail), and its transition table must equal the real state machine.
 */
class PlatformTenantsContractTest {

    static final Path DOC = Path.of("docs/api/platform-tenants.md");
    static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    private static String doc() throws Exception {
        return Files.readString(DOC);
    }

    private static Map<String, String> examples() throws Exception {
        Matcher m = Pattern.compile("```json example:([a-z0-9-]+)\\n(.*?)```", Pattern.DOTALL).matcher(doc());
        Map<String, String> out = new HashMap<>();
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    void allExamplesArePresentAndAreValidJson() throws Exception {
        Map<String, String> ex = examples();
        assertThat(ex.keySet()).containsExactlyInAnyOrder("list-response", "action-response", "noop-response",
                "in-progress-response", "error-409", "error-404");
        for (String json : ex.values()) {
            MAPPER.readTree(json);
        }
    }

    @Test
    void listExampleParsesIntoTheRealPageDto_andLeaksNoInternalFields() throws Exception {
        String json = examples().get("list-response");
        CustomApiResponse<PlatformTenantPage> r = MAPPER.readValue(json, new TypeReference<>() { });
        assertThat(r.data().items()).hasSize(1);
        assertThat(r.data().size()).isLessThanOrEqualTo(100);
        assertThat(r.data().items().get(0).status()).isIn((Object[]) java.util.Arrays.stream(TenantStatus.values()).map(Enum::name).toArray());
        assertThat(json).doesNotContain("schemaName").doesNotContain("webhookToken").doesNotContain("password");
    }

    @Test
    void actionExamplesParseIntoTheRealActionDto() throws Exception {
        for (String key : List.of("action-response", "noop-response", "in-progress-response")) {
            CustomApiResponse<TenantActionResponse> r = MAPPER.readValue(examples().get(key), new TypeReference<>() { });
            TenantAction.valueOf(r.data().action());
            TenantStatus.valueOf(r.data().previousStatus());
            TenantStatus.valueOf(r.data().tenant().status());
        }
        CustomApiResponse<TenantActionResponse> noop = MAPPER.readValue(examples().get("noop-response"), new TypeReference<>() { });
        assertThat(noop.data().changed()).isFalse();
        CustomApiResponse<TenantActionResponse> prog = MAPPER.readValue(examples().get("in-progress-response"), new TypeReference<>() { });
        assertThat(prog.data().inProgress()).isTrue();
        assertThat(prog.data().changed()).isFalse();
    }

    @Test
    void errorExamplesUseTheStandardErrorEnvelope() throws Exception {
        for (String key : List.of("error-409", "error-404")) {
            JsonNode n = MAPPER.readTree(examples().get(key));
            assertThat(n.fieldNames()).toIterable().containsExactlyInAnyOrder("description", "data");
            assertThat(n.get("data").isNull()).isTrue();
            assertThat(n.get("description").asText()).isNotBlank();
        }
    }

    @Test
    void documentedTransitionTableEqualsTheStateMachine() throws Exception {
        Matcher m = Pattern.compile("^\\| (APPROVE|SUSPEND|REACTIVATE|REPROVISION) \\| ([A-Z]+) \\| (TRANSITION|NO_OP|ILLEGAL) \\|$",
                Pattern.MULTILINE).matcher(doc());
        int rows = 0;
        while (m.find()) {
            rows++;
            assertThat(TenantStateMachine.decide(TenantAction.valueOf(m.group(1)), TenantStatus.valueOf(m.group(2))).name())
                    .as(m.group(1) + " from " + m.group(2)).isEqualTo(m.group(3));
        }
        assertThat(rows).as("one documented row per (action, status)").isEqualTo(TenantAction.values().length * TenantStatus.values().length);
    }
}
