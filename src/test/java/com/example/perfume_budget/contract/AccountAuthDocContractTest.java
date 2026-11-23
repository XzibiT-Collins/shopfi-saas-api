package com.example.perfume_budget.contract;

import com.example.perfume_budget.dto.auth.request.ChangePasswordRequest;
import com.example.perfume_budget.dto.auth.request.UpdateProfileRequest;
import com.example.perfume_budget.platform.auth.PlatformAuthProperties;
import com.example.perfume_budget.platform.auth.PlatformChangePasswordRequest;
import com.example.perfume_budget.platform.dto.StoreLookupRequest;
import com.example.perfume_budget.platform.service.OnboardingProperties;
import com.example.perfume_budget.platform.service.StoreLookupService;
import com.example.perfume_budget.service.AccountSelfService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-RD-04 / API-RD-07 / API-RD-08: the documented request examples are valid for the real DTOs, and every stable code, message and
 * number the three docs state equals the code. No Spring, no database.
 */
class AccountAuthDocContractTest {

    static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static String read(String name) throws Exception {
        return Files.readString(Path.of("docs/api", name));
    }

    private static Map<String, String> examples(String doc) {
        Matcher m = Pattern.compile("```json example:([a-z0-9-]+)\\n(.*?)```", Pattern.DOTALL).matcher(doc);
        Map<String, String> out = new HashMap<>();
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    void requestExamplesAreValidForTheRealDtos() throws Exception {
        String auth = read("auth-behaviour.md");
        var ex = examples(auth);
        ChangePasswordRequest change = MAPPER.readValue(ex.get("change-password-request"), ChangePasswordRequest.class);
        assertThat(VALIDATOR.validate(change)).isEmpty();
        UpdateProfileRequest profile = MAPPER.readValue(ex.get("update-profile-request"), UpdateProfileRequest.class);
        assertThat(VALIDATOR.validate(profile)).isEmpty();
        PlatformChangePasswordRequest platform = MAPPER.readValue(
                examples(read("platform-auth.md")).get("platform-change-password-request"), PlatformChangePasswordRequest.class);
        assertThat(VALIDATOR.validate(platform)).isEmpty();
        // and the documented limits are real: 101 characters is invalid, a blank current password is invalid
        assertThat(VALIDATOR.validate(new UpdateProfileRequest("x".repeat(101)))).isNotEmpty();
        assertThat(VALIDATOR.validate(new ChangePasswordRequest("", "N3w-Passw0rd!", null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new ChangePasswordRequest("x", "weak", null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new StoreLookupRequest("a".repeat(65)))).isNotEmpty();
        assertThat(VALIDATOR.validate(new StoreLookupRequest("   "))).isNotEmpty();
    }

    @Test
    void documentedCodesAndMessagesAreTheCodes() throws Exception {
        String auth = read("auth-behaviour.md");
        assertThat(auth).contains(AccountSelfService.CODE_CURRENT_PASSWORD_INCORRECT).contains(AccountSelfService.CODE_NO_LOCAL_PASSWORD)
                .contains("Current password is incorrect").contains("New password cannot be same as current password");
        String platform = read("platform-auth.md");
        assertThat(platform).contains("CURRENT_PASSWORD_INCORRECT").contains("Current password is incorrect");
        assertThat(read("onboarding.md")).contains(StoreLookupService.MESSAGE);
    }

    @Test
    void documentedRateLimitNumbersAreTheDefaults() throws Exception {
        PlatformAuthProperties p = new PlatformAuthProperties();
        String platform = read("platform-auth.md");
        assertThat(platform).contains("default 10 per " + p.getChangePasswordIp().getPeriodSeconds() + " s")
                .contains("default 5 per " + p.getChangePasswordAdmin().getPeriodSeconds() + " s");
        assertThat(p.getChangePasswordIp().getCapacity()).isEqualTo(10);
        assertThat(p.getChangePasswordAdmin().getCapacity()).isEqualTo(5);
        // tenant side: documented 20 / 5 per 600 s; the properties' defaults live in the @Value annotations
        String auth = read("auth-behaviour.md");
        assertThat(auth).contains("default\n  **20** per 600 s").contains("default **5** per 600 s");
        var ctor = com.example.perfume_budget.security.AuthRateLimiter.class.getDeclaredConstructors();
        String defaults = java.util.Arrays.stream(ctor).flatMap(c -> java.util.Arrays.stream(c.getParameters()))
                .map(par -> par.getAnnotation(org.springframework.beans.factory.annotation.Value.class))
                .filter(java.util.Objects::nonNull).map(v -> v.value()).reduce("", (a, b) -> a + "\n" + b);
        assertThat(defaults).contains("change-password-ip.capacity:20").contains("change-password-user.capacity:5")
                .contains("resend-otp-ip.period-seconds:600");
        OnboardingProperties o = new OnboardingProperties();
        assertThat(o.getFindStoreSlug().getCapacity()).isEqualTo(3);
        assertThat(o.getFindStoreSlug().getPeriodSeconds()).isEqualTo(3600);
        assertThat(o.getFindStoreIp().getCapacity()).isEqualTo(10);
        assertThat(o.getFindStoreIp().getPeriodSeconds()).isEqualTo(600);
    }
}
