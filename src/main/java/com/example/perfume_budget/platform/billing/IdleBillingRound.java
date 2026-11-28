package com.example.perfume_budget.platform.billing;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** The billing round of BILL-01: there is no subscription state yet, so a round does nothing. */
@Component
public class IdleBillingRound implements BillingRound {

    @Override
    public Result runOnce(Instant now) {
        return new Result(now, 0, 0, 0, 0, 0, 0);
    }
}
