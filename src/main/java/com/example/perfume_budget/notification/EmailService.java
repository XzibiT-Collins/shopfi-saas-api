package com.example.perfume_budget.notification;

import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;

public interface EmailService {
    /** EVT-01b: throws {@link MailNotSentException} when not sent (the side-effect outbox retries it); same below. */
    void sendCustomerReceipt(Payment payment);
    void sendAdminOrderNotification(Payment payment);
    void notifyAdminOnSuccessfulOrderCreation(Order order);
    /** Sends the reset link for the tenant identified by {@code tenantSlug} (its own storefront origin). */
    void sendForgotPasswordEmail(String recipientEmail, String recipientName, String token, String tenantSlug);
    void sendOtpEmail(String email, String otp);
    void notifyCustomerOnOrderStatusChange(OrderStatusChangeEvent event);
}
