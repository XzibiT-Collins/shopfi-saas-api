package com.example.perfume_budget.contract;

import com.example.perfume_budget.platform.service.OnboardingProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROV-03b (AC2), no Spring and no database: every number the onboarding doc states (rate-limit table, OTP validity,
 * lockout, cooldown, resend cap, pending TTL, tracked-key cap) must equal the default in {@link OnboardingProperties}.
 * Changing a default or a doc number without the other fails here.
 */
class OnboardingDocPropertiesTest {

    private static final String DOC;

    static {
        try {
            DOC = Files.readString(OnboardingDocExamples.DOCS.resolve("onboarding.md"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final OnboardingProperties props = new OnboardingProperties();

    @Test
    void rateLimitTableMatchesDefaults() {
        Map<String, OnboardingProperties.Limit> expected = new LinkedHashMap<>();
        expected.put("signup-ip", props.getSignupIp());
        expected.put("signup-email", props.getSignupEmail());
        expected.put("verify-ip", props.getVerifyIp());
        expected.put("verify-email", props.getVerifyEmail());
        expected.put("status-ip", props.getStatusIp());
        expected.put("resend-ip", props.getResendIp());
        expected.put("resend-email", props.getResendEmail());

        Matcher m = Pattern.compile("(?m)^\\| `([a-z-]+)` \\|[^|]*\\| (\\d+) \\| (\\d+) s \\|$").matcher(DOC);
        Map<String, long[]> documented = new LinkedHashMap<>();
        while (m.find()) {
            documented.put(m.group(1), new long[]{Long.parseLong(m.group(2)), Long.parseLong(m.group(3))});
        }
        assertEquals(expected.keySet(), documented.keySet(), "rate-limit table rows vs OnboardingProperties limiters");
        expected.forEach((name, limit) -> {
            assertEquals(limit.getCapacity(), documented.get(name)[0], name + " capacity");
            assertEquals(limit.getPeriodSeconds(), documented.get(name)[1], name + " period seconds");
        });
    }

    @Test
    void proseNumbersMatchDefaults() {
        allInts("expired code \\((\\d+) min\\)", props.getOtpValidMinutes(), "OTP validity (verify 400 row)");
        allInts("valid (\\d+) min\\) only when", props.getOtpValidMinutes(), "OTP validity (resend)");
        allInts("(\\d+) wrong codes lock the signup", props.getMaxOtpAttempts(), "max OTP attempts (verify 429 row)");
        allInts("\\((\\d+) wrong codes, \\d+ min\\)", props.getMaxOtpAttempts(), "max OTP attempts (section 7)");
        allInts("lock the signup for (\\d+) min", props.getLockoutMinutes(), "lockout minutes (verify 429 row)");
        allInts("\\(\\d+ wrong codes, (\\d+) min\\)", props.getLockoutMinutes(), "lockout minutes (section 7)");
        allInts("else (\\d+) minutes\\)", props.getLockoutMinutes(), "lockout minutes (frontend guidance)");
        allInts("older than (\\d+) h, `onboarding.pending-ttl-hours`", props.getPendingTtlHours(), "pending TTL (signup notes)");
        allInts("purged after (\\d+) h but only", props.getPendingTtlHours(), "pending TTL (state machine)");
        allInts("until the (\\d+) h purge", props.getPendingTtlHours(), "pending TTL (known gaps)");
        allInts("`onboarding.resend-cooldown-seconds`, (\\d+)\\)", props.getResendCooldownSeconds(), "resend cooldown (resend)");
        allInts("its own (\\d+) s countdown", props.getResendCooldownSeconds(), "resend cooldown (resend UI)");
        allInts("Enforce a (\\d+) s countdown", props.getResendCooldownSeconds(), "resend cooldown (guidance)");
        allInts("fewer than `onboarding.max-resends` \\((\\d+)\\)", props.getMaxResends(), "max resends (resend)");
        allInts("`max-resends` \\((\\d+)\\) failures", props.getMaxResends(), "max resends (state machine)");
        allInts("show at most (\\d+) resends", props.getMaxResends(), "max resends (guidance)");
        allInts("at most (\\d+) resends; after that", props.getMaxResends(), "max resends (known gaps)");
        allInts("`onboarding.max-tracked-keys` \\(([\\d,]+)\\)", props.getMaxTrackedKeys(), "max tracked keys");
    }

    /** Every occurrence of the pattern's first group must equal {@code expected}; at least one occurrence must exist. */
    private static void allInts(String regex, long expected, String what) {
        Matcher m = Pattern.compile(regex).matcher(DOC);
        List<Long> found = new java.util.ArrayList<>();
        while (m.find()) {
            found.add(Long.parseLong(m.group(1).replace(",", "")));
        }
        assertTrue(!found.isEmpty(), what + ": sentence not found in onboarding.md (doc reworded? update this test)");
        found.forEach(v -> assertEquals(expected, v, what));
    }
}
