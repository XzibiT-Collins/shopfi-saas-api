package com.example.perfume_budget.dto.walk_in.request;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /admin/walk-in/customers/search}: the name or e-mail travels in the body, never in the URL. */
public record CustomerSearchRequest(
        @Size(max = 100) String query
) {
}
