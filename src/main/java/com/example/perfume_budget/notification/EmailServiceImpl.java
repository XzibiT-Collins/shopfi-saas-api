package com.example.perfume_budget.notification;

import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.DeliveryDetail;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import com.example.perfume_budget.tenancy.TenantLinkBuilder;
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
    private final TenantLinkBuilder tenantLinkBuilder;

    public EmailServiceImpl(
            MailTransport mailTransport,
            TemplateEngine templateEngine,
            DeliveryDetailRepository deliveryDetailRepository,
            @Value("${perfume.budget.admin.email}") String adminEmail,
            TenantLinkBuilder tenantLinkBuilder
    ) {
        this.mailTransport = mailTransport;
        this.templateEngine = templateEngine;
        this.deliveryDetailRepository = deliveryDetailRepository;
        this.adminEmail = adminEmail;
        this.tenantLinkBuilder = tenantLinkBuilder;
    }

    @Override
    public void sendCustomerReceipt(Payment payment) {
        try{
            Order order = payment.getOrder();
            User user = getUserFromOrder(order);
            DeliveryDetail deliveryAddress = deliveryDetailRepository.findByUserAndIsDefaultTrue(user).orElse(null);
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
            throw lost("Customer receipt", payment, e);
        }
    }

    @Override
    public void sendAdminOrderNotification(Payment payment) {
        try{
            Order order = payment.getOrder();
            Context context = new Context();
            context.setVariable("order", order);
            context.setVariable("payment", payment);
            String subject = "Payment successful for order #" + order.getOrderNumber() + ": Awaiting Processing and Delivery";

            String htmlContent = templateEngine.process("success-payment", context);
            sendEmail(htmlContent, adminEmail, subject);
        }catch (Exception e){
            throw lost("Admin payment e-mail", payment, e);
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
            throw new MailNotSentException("Admin new-order e-mail", "order " + (order == null ? null : order.getOrderNumber()), e);
        }
    }

    @Override
    public void sendForgotPasswordEmail(String recipientEmail, String recipientName, String token, String tenantSlug) {
        try{
            Context context = new Context();
            String resetPasswordUrl = tenantLinkBuilder.resetPasswordLink(tenantSlug, token);
            context.setVariable("recipientName", recipientName);
            context.setVariable("resetPasswordLink", resetPasswordUrl);

            String htmlContent = templateEngine.process("forgot-password", context);
            sendEmail(htmlContent, recipientEmail, "Password Reset Request");
            log.info("Password reset e-mail sent (tenant {})", tenantSlug);
        }catch (Exception e){
            logLost("Password reset e-mail", "tenant " + tenantSlug, e);
        }
    }

    @Override
    public void sendStoreLoginLinkEmail(String ownerEmail, String storeName, String tenantSlug) {
        try{
            Context context = new Context();
            context.setVariable("storeName", storeName);
            context.setVariable("loginLink", tenantLinkBuilder.adminLoginLink(tenantSlug)
                    .orElseThrow(() -> new IllegalStateException("No tenant origin for tenant " + tenantSlug)));

            String htmlContent = templateEngine.process("store-login-link", context);
            sendEmail(htmlContent, ownerEmail, "Your store sign-in link");
            log.info("Store sign-in link e-mail sent (tenant {})", tenantSlug);
        }catch (Exception e){
            logLost("Store sign-in link e-mail", "tenant " + tenantSlug, e);
        }
    }

    @Override
    public void sendOtpEmail(String recipientEmail, String otpCode) {
        try{
            Context context = new Context();
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("verify-otp", context);

            sendEmail(htmlContent, recipientEmail, "Verify OTP");
            log.info("OTP e-mail sent");
        }catch (Exception e){
            logLost("OTP e-mail", "an OTP request", e);
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
            log.info("Order status e-mail sent for order {}", event.orderNumber());
        }catch (Exception e){
            logLost("Order status e-mail", "order " + event.orderNumber(), e);
        }
    }

    // HELPER METHODS

    /**
     * EVT-01b (L2): the receipt, admin payment and admin new-order e-mails run from the side-effect outbox, so they
     * THROW {@link MailNotSentException} (reference + exception class, no cause) and the outbox logs and retries.
     * EVT-01: a receipt / order e-mail that was not sent is logged with the order / payment reference (so it can be
     * re-sent), never with the recipient's address or the exception text (a transport error can quote it). Rework 1
     * (F3): the stack trace is no longer logged at all (its messages can carry the address); OTP / reset e-mails log
     * no address either, only the tenant.
     */
    private static MailNotSentException lost(String what, Payment payment, Exception e) {
        Order order = payment == null ? null : payment.getOrder();
        return new MailNotSentException(what, "order " + (order == null ? null : order.getOrderNumber()) + ", payment "
                + (payment == null ? null : payment.getId()) + " (ref "
                + (payment == null ? null : payment.getProviderPaymentReference()) + ")", e);
    }

    private static void logLost(String what, String reference, Exception e) {
        log.error("{} for {} was NOT sent: {}", what, reference, e.getClass().getName());
    }

    private void sendEmail(String htmlContent, String recipientEmail, String subject) {
        mailTransport.send(new MailMessage(adminEmail, recipientEmail, subject, htmlContent));
    }

    private User getUserFromOrder(Order order) {
        return order != null ? order.getUser() : null;
    }
}
