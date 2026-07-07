package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.customer.CustomerDataResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.projection.CustomerOrderSummary;

public class CustomerDataMapper {
    private CustomerDataMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static CustomerDataResponse toCustomerDataResponse(CustomerOrderSummary summary){
        return CustomerDataResponse.builder()
                .id(summary.getUserId())
                .fullName(summary.getFullName())
                .email(summary.getEmail())
                .orderCount(summary.getTotalOrders())
                .totalSpent(new Money(summary.getTotalAmountSpent(), CurrencyCode.GHS).toString())
                .isActive(summary.getIsActive())
                .dateJoined(summary.getCreatedAt() != null ? summary.getCreatedAt().toLocalDate() : null)
                .build();
    }
}
