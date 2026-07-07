package com.example.perfume_budget.notification;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DeliveryLabel;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentProvider;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.DeliveryDetail;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private MailTransport mailTransport;

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private DeliveryDetailRepository deliveryDetailRepository;

    private EmailServiceImpl emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailServiceImpl(
                mailTransport,
                templateEngine,
                deliveryDetailRepository,
                "admin@example.com",
                "https://frontend.example.com/reset-password"
        );
    }

    @Test
    void sendOtpEmail_rendersVerifyOtpTemplateAndDelegatesToTransport() {
        when(templateEngine.process(eq("verify-otp"), any(Context.class))).thenReturn("<p>otp</p>");

        emailService.sendOtpEmail("user@example.com", "123456");

        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("verify-otp"), contextCaptor.capture());
        assertEquals("123456", contextCaptor.getValue().getVariable("otpCode"));
        verify(mailTransport).send(new MailMessage("admin@example.com", "user@example.com", "Verify OTP", "<p>otp</p>"));
    }

    @Test
    void sendForgotPasswordEmail_buildsResetLinkAndDelegatesToTransport() {
        when(templateEngine.process(eq("forgot-password"), any(Context.class))).thenReturn("<p>reset</p>");

        emailService.sendForgotPasswordEmail("user@example.com", "Jane Doe", "reset-token");

        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("forgot-password"), contextCaptor.capture());
        assertEquals("Jane Doe", contextCaptor.getValue().getVariable("recipientName"));
        assertEquals("https://frontend.example.com/reset-password?resetPasswordToken=reset-token",
                contextCaptor.getValue().getVariable("resetPasswordLink"));
        verify(mailTransport).send(new MailMessage("admin@example.com", "user@example.com", "Password Reset Request", "<p>reset</p>"));
    }

    @Test
    void notifyCustomerOnOrderStatusChange_rendersStatusTemplateAndDelegatesToTransport() {
        when(templateEngine.process(eq("order-status-update"), any(Context.class))).thenReturn("<p>status</p>");

        emailService.notifyCustomerOnOrderStatusChange(
                OrderStatusChangeEvent.builder()
                        .customerEmail("user@example.com")
                        .orderNumber("ORD-123")
                        .status(OrderProcessingStatus.OUT_FOR_DELIVERY)
                        .build()
        );

        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("order-status-update"), contextCaptor.capture());
        assertEquals("ORD-123", contextCaptor.getValue().getVariable("orderNumber"));
        assertEquals(OrderProcessingStatus.OUT_FOR_DELIVERY, contextCaptor.getValue().getVariable("orderStatus"));
        verify(mailTransport).send(new MailMessage("admin@example.com", "user@example.com", "Order Status Update", "<p>status</p>"));
    }

    @Test
    void sendCustomerReceipt_rendersReceiptTemplateAndDelegatesToTransport() {
        User user = User.builder()
                .id(1L)
                .fullName("Jane Doe")
                .email("user@example.com")
                .build();

        Order order = Order.builder()
                .id(10L)
                .orderNumber("ORD-123")
                .user(user)
                .build();

        Payment payment = Payment.builder()
                .id(20L)
                .order(order)
                .customerEmail("user@example.com")
                .provider(PaymentProvider.PAYSTACK)
                .amount(new BigDecimal("100.00"))
                .status(PaymentStatus.COMPLETED)
                .build();

        DeliveryDetail deliveryDetail = DeliveryDetail.builder()
                .id(30L)
                .user(user)
                .recipientName("Jane Doe")
                .phoneNumber("0240000000")
                .addressLine1("123 Main St")
                .city("Accra")
                .region("Greater Accra")
                .label(DeliveryLabel.HOME)
                .isDefault(true)
                .build();

        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(user)).thenReturn(Optional.of(deliveryDetail));
        when(templateEngine.process(eq("order-receipt"), any(Context.class))).thenReturn("<p>receipt</p>");

        emailService.sendCustomerReceipt(payment);

        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("order-receipt"), contextCaptor.capture());
        assertEquals("Jane Doe", contextCaptor.getValue().getVariable("recipientName"));
        assertEquals("0240000000", contextCaptor.getValue().getVariable("phone"));
        assertEquals("123 Main St", contextCaptor.getValue().getVariable("address"));
        assertEquals("Accra", contextCaptor.getValue().getVariable("city"));
        verify(mailTransport).send(new MailMessage("admin@example.com", "user@example.com", "Your Order Receipt - ORD-123", "<p>receipt</p>"));
    }

    @Test
    void sendAdminOrderNotification_rendersAdminTemplateAndDelegatesToTransport() {
        Order order = Order.builder()
                .id(10L)
                .orderNumber("ORD-123")
                .build();

        Payment payment = Payment.builder()
                .id(20L)
                .order(order)
                .build();

        when(templateEngine.process(eq("success-payment"), any(Context.class))).thenReturn("<p>admin</p>");

        emailService.sendAdminOrderNotification(payment);

        verify(mailTransport).send(new MailMessage(
                "admin@example.com",
                "admin@example.com",
                "Payment successful for order #ORD-123: Awaiting Processing and Delivery",
                "<p>admin</p>"
        ));
    }

    @Test
    void notifyAdminOnSuccessfulOrderCreation_rendersOrderTemplateAndDelegatesToTransport() {
        Order order = Order.builder()
                .id(10L)
                .orderNumber("ORD-123")
                .build();

        when(templateEngine.process(eq("success-order"), any(Context.class))).thenReturn("<p>created</p>");

        emailService.notifyAdminOnSuccessfulOrderCreation(order);

        verify(mailTransport).send(new MailMessage(
                "admin@example.com",
                "admin@example.com",
                "New Order Received - ORD-123: Awaiting payment from customer",
                "<p>created</p>"
        ));
    }
}
