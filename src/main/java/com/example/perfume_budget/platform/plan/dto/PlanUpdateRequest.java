package com.example.perfume_budget.platform.plan.dto;

import com.example.perfume_budget.dto.json.StrictIntegerDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code PUT /api/v1/platform/plans/{id}}: the version the client read (optimistic lock) plus every writable field.
 * {@code code} is not part of the contract body; if sent, a value different from the stored code is a 400 and an equal
 * one is ignored.
 */
@Getter
@Setter
public class PlanUpdateRequest extends PlanWriteRequest {

    @NotNull
    @Min(0)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer version;

    private String code;
}
