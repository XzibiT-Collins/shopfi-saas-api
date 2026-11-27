package com.example.perfume_budget.platform.plan.dto;

import com.example.perfume_budget.dto.json.StrictIntegerDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code PUT /api/v1/platform/plans/matrix} (plans.md section 8): 1..50 items, each with the version the client read and
 * at least one of {@code entitlements} (1..16 changes) / {@code plan} (a full replacement of the writable fields).
 */
public record PlanMatrixRequest(@NotNull @Size(min = 1, max = 50) @Valid List<@NotNull @Valid Item> plans) {

    public record Item(
            @NotNull @Min(1) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer id,
            @NotNull @Min(0) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer version,
            @Size(min = 1, max = 16) @Valid List<@NotNull @Valid EntitlementWrite> entitlements,
            @Valid PlanWriteRequest plan) {
    }
}
