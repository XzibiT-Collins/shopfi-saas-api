package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.domain.CustomDomainService;
import com.example.perfume_budget.platform.domain.CustomDomainView;
import com.example.perfume_budget.platform.domain.CustomDomains;
import com.example.perfume_budget.platform.domain.PlatformCustomDomainView;
import com.example.perfume_budget.platform.enums.CustomDomainStatus;
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

/** DOMAIN-02 AC3: docs/api/custom-domains.md examples parse into the real DTOs and every error code is documented. */
class CustomDomainsContractTest {

    static final Path DOC = Path.of("docs/api/custom-domains.md");
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static Map<String, String> examples() throws Exception {
        Matcher m = Pattern.compile("```json example:([a-z0-9-]+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(DOC));
        Map<String, String> out = new HashMap<>();
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    void examplesParseIntoTheRealDtos() throws Exception {
        Map<String, String> ex = examples();
        assertThat(ex.keySet()).containsExactlyInAnyOrder("view-response", "platform-response", "error-400");
        CustomApiResponse<CustomDomainView> v = MAPPER.readValue(ex.get("view-response"), new TypeReference<>() { });
        CustomDomainStatus.valueOf(v.data().status());
        assertThat(v.data().txtRecordName()).isEqualTo("_shopfi-verification." + v.data().domain());
        CustomApiResponse<PlatformCustomDomainView> p = MAPPER.readValue(ex.get("platform-response"), new TypeReference<>() { });
        CustomDomainStatus.valueOf(p.data().customDomain().status());
        assertThat(p.data().history()).isNotEmpty();
        JsonNode err = MAPPER.readTree(ex.get("error-400"));
        assertThat(CustomDomains.codes()).contains(err.get("data").get("code").asText());
    }

    @Test
    void everyErrorCodeIsDocumented() throws Exception {
        String doc = Files.readString(DOC);
        List<String> codes = new java.util.ArrayList<>(CustomDomains.codes());
        codes.addAll(List.of(CustomDomainService.TAKEN, CustomDomainService.NOT_SET, CustomDomainService.BUSY,
                CustomDomainService.VERIFY_RATE_LIMITED, CustomDomainService.CHANGE_RATE_LIMITED,
                CustomDomainService.DNS_UNAVAILABLE, CustomDomainService.TXT_NOT_FOUND));
        for (String c : codes) {
            assertThat(doc).as(c).contains("`" + c + "`");
        }
        for (CustomDomainStatus s : CustomDomainStatus.values()) {
            assertThat(doc).contains("| `" + s.name() + "` |");
        }
    }
}
