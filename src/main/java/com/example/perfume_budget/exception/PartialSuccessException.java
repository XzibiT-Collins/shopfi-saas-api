package com.example.perfume_budget.exception;

import java.util.List;

public class PartialSuccessException extends RuntimeException {
    private final List<String> failedItems;

    public PartialSuccessException(String message, List<String> failedItems) {
        super(message);
        this.failedItems = failedItems;
    }

    public List<String> getFailedItems() {
        return failedItems;
    }
}