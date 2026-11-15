package com.example.perfume_budget.coupon;

import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.json.StrictIntegerDeserializer.InvalidWholeNumberException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API-04 rework: usageLimit / perUserUsageLimit are strict JSON integers (no 1.5 -> 1, no "7" -> 7). */
class CouponStrictIntegerJsonTest {

    private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule());

    private static String body(String field, String raw) {
        String usage = field.equals("usageLimit") ? raw : "10";
        String perUser = field.equals("perUserUsageLimit") ? raw : "null";
        return "{\"discountType\":\"FLAT\",\"discountValue\":5,\"usageLimit\":" + usage + ",\"perUserUsageLimit\":" + perUser
                + ",\"isActive\":true,\"startDate\":\"2026-01-01\",\"expirationDate\":\"2026-02-01\"}";
    }

    @Test
    void integersAreAccepted() throws Exception {
        CouponRequest r = M.readValue(body("perUserUsageLimit", "3"), CouponRequest.class);
        assertThat(r.usageLimit()).isEqualTo(10);
        assertThat(r.perUserUsageLimit()).isEqualTo(3);
        assertThat(M.readValue(body("perUserUsageLimit", "null"), CouponRequest.class).perUserUsageLimit()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"usageLimit", "perUserUsageLimit"})
    void decimalsStringsAndOverflowAreRejectedWithAFieldMessage(String field) {
        assertThatThrownBy(() -> M.readValue(body(field, "1.5"), CouponRequest.class))
                .isInstanceOf(InvalidWholeNumberException.class)
                .satisfies(e -> assertThat(((InvalidWholeNumberException) e).getOriginalMessage())
                        .isEqualTo(field + " must be a whole number"));
        assertThatThrownBy(() -> M.readValue(body(field, "\"7\""), CouponRequest.class))
                .isInstanceOf(InvalidWholeNumberException.class)
                .satisfies(e -> assertThat(((InvalidWholeNumberException) e).getOriginalMessage())
                        .startsWith(field + " must be a whole number"));
        assertThatThrownBy(() -> M.readValue(body(field, "1E999999999"), CouponRequest.class))
                .isInstanceOf(InvalidWholeNumberException.class)
                .satisfies(e -> assertThat(((InvalidWholeNumberException) e).getOriginalMessage())
                        .isEqualTo(field + " must be a whole number"));
        assertThatThrownBy(() -> M.readValue(body(field, "99999999999"), CouponRequest.class))
                .isInstanceOf(InvalidWholeNumberException.class)
                .satisfies(e -> assertThat(((InvalidWholeNumberException) e).getOriginalMessage())
                        .isEqualTo(field + " is out of range"));
        assertThatThrownBy(() -> M.readValue(body(field, "true"), CouponRequest.class))
                .isInstanceOf(InvalidWholeNumberException.class);
    }
}
