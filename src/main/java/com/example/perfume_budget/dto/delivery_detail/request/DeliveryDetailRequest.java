package com.example.perfume_budget.dto.delivery_detail.request;

import com.example.perfume_budget.enums.DeliveryLabel;
import jakarta.validation.constraints.NotNull;

public record DeliveryDetailRequest(
        @NotNull
        String recipientName,
        @NotNull
        String phoneNumber,
        String alternatePhoneNumber,
        @NotNull
        String addressLine1,
        String addressLine2,
        @NotNull
        String city,
        @NotNull
        String region,
        /** Optional (nullable column): omitted or null is stored as null. */
        String landmark,
        /** Optional on create: defaults to {@link DeliveryLabel#HOME} (the column is NOT NULL). */
        DeliveryLabel label,
        boolean isDefault
) {}
