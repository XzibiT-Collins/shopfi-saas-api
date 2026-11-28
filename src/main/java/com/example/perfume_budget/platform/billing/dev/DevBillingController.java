package com.example.perfume_budget.platform.billing.dev;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.platform.billing.BillingClock;
import com.example.perfume_budget.platform.billing.BillingRound;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev-only controls for the real-stack e2e (billing.md 19.2): read and move the BILLING clock and run one billing round.
 * It moves ONLY {@link BillingClock} (the global {@code Clock} that JWT expiry, OTP and rate limits use is never touched).
 * The bean exists only when {@code tenant.dev-endpoints.enabled=true} AND the {@code prod} profile is not active (two
 * independent switches, a test proves the bean is absent under {@code prod}); without the property the SecurityConfig
 * matcher {@code /api/v1/dev/** -> denyAll} still refuses every route. Same pattern as DevTenantController.
 */
@RestController
@Profile("!prod")
@ConditionalOnProperty(name = "tenant.dev-endpoints.enabled", havingValue = "true")
@RequestMapping("/api/v1/dev/billing")
@RequiredArgsConstructor
public class DevBillingController {

    private static final long MAX_ADVANCE_SECONDS = 63_072_000L;

    private final BillingClock billingClock;
    private final BillingRound round;

    @GetMapping("/clock")
    public ResponseEntity<CustomApiResponse<Map<String, Object>>> clock() {
        return ResponseEntity.ok(CustomApiResponse.success(state()));
    }

    /** Exactly one of {@code {seconds}}, {@code {to}}, {@code {reset: true}}. */
    @PostMapping(path = "/clock/advance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomApiResponse<Map<String, Object>>> advance(@RequestBody JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new BadRequestException("Request body is missing or malformed.");
        }
        boolean seconds = request.has("seconds");
        boolean to = request.has("to");
        boolean reset = request.has("reset");
        if ((seconds ? 1 : 0) + (to ? 1 : 0) + (reset ? 1 : 0) != 1) {
            throw new BadRequestException("Send exactly one of seconds, to or reset.");
        }
        if (seconds) {
            JsonNode s = request.get("seconds");
            if (!s.isIntegralNumber() || !s.canConvertToLong() || s.longValue() < 1 || s.longValue() > MAX_ADVANCE_SECONDS) {
                throw new BadRequestException("seconds must be a whole number between 1 and " + MAX_ADVANCE_SECONDS + ".");
            }
            billingClock.advanceSeconds(s.longValue());
        } else if (to) {
            try {
                billingClock.moveTo(Instant.parse(request.get("to").asText()));
            } catch (DateTimeParseException e) {
                throw new BadRequestException("to must be a UTC instant ending in Z.");
            }
        } else {
            if (!request.get("reset").isBoolean() || !request.get("reset").booleanValue()) {
                throw new BadRequestException("reset must be true.");
            }
            billingClock.reset();
        }
        return ResponseEntity.ok(CustomApiResponse.success(state()));
    }

    /** One billing round, synchronously, at the billing clock's now. */
    @PostMapping("/run")
    public ResponseEntity<CustomApiResponse<BillingRound.Result>> run() {
        return ResponseEntity.ok(CustomApiResponse.success(round.runOnce(billingClock.now())));
    }

    private Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", billingClock.now());
        out.put("offsetSeconds", billingClock.offsetSeconds());
        out.put("frozen", false);
        return out;
    }
}
