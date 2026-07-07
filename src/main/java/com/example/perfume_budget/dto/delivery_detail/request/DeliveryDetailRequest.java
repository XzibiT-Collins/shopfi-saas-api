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
        @NotNull
        String landmark,
        DeliveryLabel label,
        boolean isDefault
) {}
