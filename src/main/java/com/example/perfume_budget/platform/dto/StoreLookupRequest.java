package com.example.perfume_budget.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** API-RD-04 "Find your store": the store name (slug) the visitor typed. Capped before anything is looked up. */
public record StoreLookupRequest(@NotBlank @Size(max = 64) String slug) {
}
