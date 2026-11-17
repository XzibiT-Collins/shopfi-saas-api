package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.exception.CouponRejectedException;
import com.example.perfume_budget.exception.CouponRejectedException.Reason;
import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-06: the checkout / GET /tax 400 for a refused coupon. Only the per-user limit carries data.code (the same code
 * the cart preview reports); every other reason keeps the old uncoded 400 so it reveals nothing about which codes exist.
 */
class CouponRejectedResponseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void perUserLimitIs400WithCodeAndTheUnchangedDescription() {
        ResponseEntity<CustomApiResponse<Object>> r = handler.handleCouponRejected(
                new CouponRejectedException(Reason.PER_USER_LIMIT, CouponPerUserLimit.MESSAGE));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().description()).isEqualTo("You have already used this coupon the maximum number of times");
        assertThat(r.getBody().data()).isEqualTo(Map.of("code", "COUPON_PER_USER_LIMIT"));
    }

    @Test
    void everyOtherReasonIsTheOldUncoded400() {
        for (Reason reason : Reason.values()) {
            if (reason == Reason.PER_USER_LIMIT) continue;
            ResponseEntity<CustomApiResponse<Object>> r = handler.handleCouponRejected(new CouponRejectedException(reason, "msg " + reason));
            assertThat(r.getStatusCode().value()).as(reason.name()).isEqualTo(400);
            assertThat(r.getBody().description()).isEqualTo("msg " + reason);
            assertThat(r.getBody().data()).as(reason.name()).isNull();
        }
    }
}
