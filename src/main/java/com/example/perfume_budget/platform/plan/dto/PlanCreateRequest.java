package com.example.perfume_budget.platform.plan.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** {@code POST /api/v1/platform/plans}: {@code code} (immutable afterwards) plus every writable field. */
@Getter
@Setter
public class PlanCreateRequest extends PlanWriteRequest {

    @NotNull
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "must be 2 to 32 characters: an upper-case letter, then upper-case letters, digits or _")
    private String code;
}
