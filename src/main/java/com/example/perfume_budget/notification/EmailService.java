package com.example.perfume_budget.notification;

import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;

public interface EmailService {
    void sendCustomerReceipt(Payment payment);
    void sendAdminOrderNotification(Payment payment);
    void notifyAdminOnSuccessfulOrderCreation(Order order);
    void sendForgotPasswordEmail(String recipientEmail, String recipientName, String token);
    void sendOtpEmail(String email, String otp);
    void notifyCustomerOnOrderStatusChange(OrderStatusChangeEvent event);
}
