package com.example.perfume_budget.dto.delivery_detail.response;

import com.example.perfume_budget.enums.DeliveryLabel;
import lombok.Builder;

@Builder
public record DeliveryDetailResponse(
        Long id,
        String recipientName,
        String phoneNumber,
        String alternatePhoneNumber,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String landmark,
        DeliveryLabel label,
        boolean isDefault
) {}
