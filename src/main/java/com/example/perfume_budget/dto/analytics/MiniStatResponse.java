package com.example.perfume_budget.dto.analytics;

public record MiniStatResponse(
        String label,
        String value,
        String trend,
        boolean isUp
) {}
