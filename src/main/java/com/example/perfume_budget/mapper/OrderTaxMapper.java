package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.tax.OrderTaxResponse;
import com.example.perfume_budget.model.OrderTax;

public class OrderTaxMapper {
    private OrderTaxMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static OrderTaxResponse toOrderTaxResponse(OrderTax orderTax){
        return OrderTaxResponse.builder()
                .id(orderTax.getId())
                .taxName(orderTax.getTaxName())
                .taxAmount(orderTax.getTaxAmount().getAmount())
                .taxRate(orderTax.getTaxRate())
                .build();
    }
}
