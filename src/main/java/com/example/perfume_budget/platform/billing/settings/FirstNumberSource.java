package com.example.perfume_budget.platform.billing.settings;

import org.springframework.stereotype.Component;

/** Default {@link NextNumberSource}: nothing has been issued yet, so the next number is 1. */
@Component
public class FirstNumberSource implements NextNumberSource {

    @Override
    public long next(DocumentType type, int year) {
        return 1;
    }
}
