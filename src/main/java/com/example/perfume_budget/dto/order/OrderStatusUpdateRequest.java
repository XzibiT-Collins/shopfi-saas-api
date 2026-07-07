package com.example.perfume_budget.dto.order;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import jakarta.validation.constraints.NotNull;

public record OrderStatusUpdateRequest(
        @NotNull OrderProcessingStatus orderStatus
) {}
