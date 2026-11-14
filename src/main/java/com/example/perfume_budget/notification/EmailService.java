package com.example.perfume_budget.notification;

import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;

public interface EmailService {
    void sendCustomerReceipt(Payment payment);
    void sendAdminOrderNotification(Payment payment);
    void notifyAdminOnSuccessfulOrderCreation(Order order);
    /** Sends the reset link for the tenant identified by {@code tenantSlug} (its own storefront origin). */
    void sendForgotPasswordEmail(String recipientEmail, String recipientName, String token, String tenantSlug);

    /** Convenience for callers on a tenant request thread: uses the tenant bound to the current context. */
    default void sendForgotPasswordEmail(String recipientEmail, String recipientName, String token) {
        sendForgotPasswordEmail(recipientEmail, recipientName, token,
                com.example.perfume_budget.tenancy.TenantContext.getSlug());
    }
    void sendOtpEmail(String email, String otp);
    void notifyCustomerOnOrderStatusChange(OrderStatusChangeEvent event);
}
