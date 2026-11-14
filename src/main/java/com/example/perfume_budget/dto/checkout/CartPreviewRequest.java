package com.example.perfume_budget.dto.checkout;

import jakarta.validation.constraints.Size;

/** Both fields optional. Amounts are never accepted from the client. */
public record CartPreviewRequest(
        @Size(max = 64) String couponCode,
        Long deliveryDetailId
) {}
