package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderItemResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.model.WalkInOrder;
import com.example.perfume_budget.model.WalkInOrderItem;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class WalkInOrderMapper {
    private WalkInOrderMapper() {
        throw new IllegalStateException("Utility class");
    }

    public static WalkInOrderResponse toResponse(WalkInOrder order) {
        return WalkInOrderResponse.builder()
                .orderNumber(order.getOrderNumber())
                .customerName(resolveCustomerName(order))
                .customerPhone(resolveCustomerPhone(order))
                .processedBy(order.getProcessedBy().getFullName())
                .discountType(order.getDiscountType())
                .discountValue(order.getDiscountValue() != null ? formatMoney(order.getDiscountValue()) : formatMoney(BigDecimal.ZERO))
                .paymentMethod(order.getPaymentMethod())
                .status(order.getStatus())
                .subtotal(formatMoney(order.getSubtotal().getAmount()))
                .originalSubtotal(formatMoney(order.getSubtotal().getAmount().add(automaticDiscount(order))))
                .automaticDiscountAmount(formatMoney(automaticDiscount(order)))
                .discountAmount(formatMoney(order.getDiscountAmount().getAmount()))
                .totalTaxAmount(formatMoney(order.getTotalTaxAmount().getAmount()))
                .totalAmount(formatMoney(order.getTotalAmount().getAmount()))
                .amountPaid(formatMoney(order.getAmountPaid().getAmount()))
                .changeGiven(formatMoney(order.getChangeGiven().getAmount()))
                .receiptPrinted(order.getReceiptPrinted())
                .createdAt(order.getCreatedAt())
                .items(order.getItems().stream().map(WalkInOrderMapper::toItemResponse).toList())
                .build();
    }

    public static CustomerSearchResponse toCustomerSearchResponse(User user) {
        return CustomerSearchResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getProfile() != null ? user.getProfile().getPhoneNumber() : null)
                .build();
    }

    private static WalkInOrderItemResponse toItemResponse(WalkInOrderItem item) {
        return WalkInOrderItemResponse.builder()
                .productName(item.getProductName())
                .sku(item.getSku())
                .quantity(item.getQuantity())
                .unitPrice(formatMoney(item.getUnitPrice()))
                .totalPrice(formatMoney(item.getTotalPrice()))
                .build();
    }

    private static String resolveCustomerName(WalkInOrder order) {
        if (order.getRegisteredUser() != null) {
            return order.getRegisteredUser().getFullName();
        }
        if (order.getWalkInCustomer() != null && order.getWalkInCustomer().getName() != null && !order.getWalkInCustomer().getName().isBlank()) {
            return order.getWalkInCustomer().getName();
        }
        return "Anonymous";
    }

    private static String resolveCustomerPhone(WalkInOrder order) {
        if (order.getRegisteredUser() != null
                && order.getRegisteredUser().getProfile() != null
                && order.getRegisteredUser().getProfile().getPhoneNumber() != null
                && !order.getRegisteredUser().getProfile().getPhoneNumber().isBlank()) {
            return order.getRegisteredUser().getProfile().getPhoneNumber();
        }
        if (order.getWalkInCustomer() != null && order.getWalkInCustomer().getPhone() != null && !order.getWalkInCustomer().getPhone().isBlank()) {
            return order.getWalkInCustomer().getPhone();
        }
        return "N/A";
    }

    private static BigDecimal automaticDiscount(WalkInOrder order) {
        return order.getAutomaticDiscountAmount() != null && order.getAutomaticDiscountAmount().getAmount() != null
                ? order.getAutomaticDiscountAmount().getAmount()
                : BigDecimal.ZERO;
    }

    private static String formatMoney(BigDecimal amount) {
        return "GHS " + amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
