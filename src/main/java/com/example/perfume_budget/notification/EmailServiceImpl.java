package com.example.perfume_budget.notification;

import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.DeliveryDetail;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Slf4j
@Component
public class EmailServiceImpl implements EmailService{
    private final MailTransport mailTransport;
    private final TemplateEngine templateEngine;
    private final DeliveryDetailRepository deliveryDetailRepository;
    private final String adminEmail;
    private final String changePasswordUrl;

    public EmailServiceImpl(
            MailTransport mailTransport,
            TemplateEngine templateEngine,
            DeliveryDetailRepository deliveryDetailRepository,
            @Value("${perfume.budget.admin.email}") String adminEmail,
            @Value("${frontend.change-password-url}") String changePasswordUrl
    ) {
        this.mailTransport = mailTransport;
        this.templateEngine = templateEngine;
        this.deliveryDetailRepository = deliveryDetailRepository;
        this.adminEmail = adminEmail;
        this.changePasswordUrl = changePasswordUrl;
    }

    @Override
    public void sendCustomerReceipt(Payment payment) {
        Order order = payment.getOrder();
        User user = getUserFromOrder(order);
        DeliveryDetail deliveryAddress = deliveryDetailRepository.findByUserAndIsDefaultTrue(user).orElse(null);
        try{
            Context context = new Context();
            context.setVariable("recipientName", user.getFullName());
            context.setVariable("order", order);
            context.setVariable("payment", payment);
            context.setVariable("phone", deliveryAddress.getPhoneNumber());
            context.setVariable("address", deliveryAddress.getAddressLine1());
            context.setVariable("city", deliveryAddress.getCity());
            String subject = "Your Order Receipt - " + order.getOrderNumber();

            String htmlContent = templateEngine.process("order-receipt", context);
            sendEmail(htmlContent, user.getEmail(), subject);
        }catch (Exception e){
            log.error("Failed to send email to {}",user != null ? user.getEmail() : "unknown recipient", e);
        }
    }

    @Override
    public void sendAdminOrderNotification(Payment payment) {
        Order order = payment.getOrder();
        try{
            Context context = new Context();
            context.setVariable("order", order);
            context.setVariable("payment", payment);
            String subject = "Payment successful for order #" + order.getOrderNumber() + ": Awaiting Processing and Delivery";

            String htmlContent = templateEngine.process("success-payment", context);
            sendEmail(htmlContent, adminEmail, subject);
        }catch (Exception e){
            log.error("Failed to send email to admin about new order received.", e);
        }
    }

    @Override
    public void notifyAdminOnSuccessfulOrderCreation(Order order) {
        try{
            Context context = new Context();
            context.setVariable("order", order);
            String subject = "New Order Received - " + order.getOrderNumber() + ": Awaiting payment from customer";

            String htmlContent = templateEngine.process("success-order", context);
            sendEmail(htmlContent, adminEmail, subject);
            log.info("Admin notified of new order: {}", order.getOrderNumber());
        }catch (Exception e){
            log.error("Failed to send admin notification: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendForgotPasswordEmail(String recipientEmail, String recipientName, String token) {
        try{
            Context context = new Context();
            String resetPasswordUrl = changePasswordUrl+"?resetPasswordToken=" + token;
            log.info("Reset Password URL: {}", resetPasswordUrl);
            context.setVariable("recipientName", recipientName);
            context.setVariable("resetPasswordLink", resetPasswordUrl);

            String htmlContent = templateEngine.process("forgot-password", context);
            sendEmail(htmlContent, recipientEmail, "Password Reset Request");
            log.info("Forgot Password Email Sent to {}", recipientEmail);
        }catch (Exception e){
            log.error("Failed to send forgot password email to {}", recipientEmail, e);
        }
    }

    @Override
    public void sendOtpEmail(String recipientEmail, String otpCode) {
        try{
            Context context = new Context();
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("verify-otp", context);

            sendEmail(htmlContent, recipientEmail, "Verify OTP");
            log.info("OTP Email Sent to {}", recipientEmail);
        }catch (Exception e){
            log.error("Failed to send otp email to {}", recipientEmail, e);
        }
    }

    @Override
    public void notifyCustomerOnOrderStatusChange(OrderStatusChangeEvent event) {
        try{
            Context context = new Context();
            context.setVariable("orderNumber", event.orderNumber());
            context.setVariable("orderStatus", event.status());

            String htmlContent = templateEngine.process("order-status-update", context);

            sendEmail(htmlContent, event.customerEmail(), "Order Status Update");
            log.info("Order Status Update Email Sent to {}", event.customerEmail());
        }catch (Exception e){
            log.error("Failed to send order status update email", e);
        }
    }

    // HELPER METHODS
    private void sendEmail(String htmlContent, String recipientEmail, String subject) {
        mailTransport.send(new MailMessage(adminEmail, recipientEmail, subject, htmlContent));
    }

    private User getUserFromOrder(Order order) {
        return order != null ? order.getUser() : null;
    }
}
