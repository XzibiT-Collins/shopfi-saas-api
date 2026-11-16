package com.example.perfume_budget.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.DeliveryDetail;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import com.example.perfume_budget.support.TestLinkBuilders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * EVT-01 (d): a receipt / order e-mail that cannot be sent after the commit (transport error, missing delivery
 * address, template error) is logged at ERROR with the order / payment reference so it can be re-sent, and without
 * the customer's e-mail address or the exception text (which can carry it).
 */
class ReceiptFailureLoggingTest {

    private static final String CUSTOMER = "buyer@shop.test";

    private MailTransport transport;
    private TemplateEngine engine;
    private DeliveryDetailRepository addresses;
    private EmailServiceImpl service;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        transport = mock(MailTransport.class);
        engine = mock(TemplateEngine.class);
        addresses = mock(DeliveryDetailRepository.class);
        when(engine.process(anyString(), any(Context.class))).thenReturn("<p>mail</p>");
        doThrow(new MailDeliveryException("550 mailbox " + CUSTOMER + " unavailable")).when(transport).send(any());
        service = new EmailServiceImpl(transport, engine, addresses, "admin@shop.test",
                TestLinkBuilders.linkBuilder("shopfi.test", "https://frontend.example.com/reset-password"));
        logger = (Logger) LoggerFactory.getLogger(EmailServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private Payment payment() {
        User u = new User();
        u.setEmail(CUSTOMER);
        u.setFullName("Buyer");
        Order o = new Order();
        o.setId(9L);
        o.setOrderNumber("PB-9");
        o.setUser(u);
        Payment p = new Payment();
        p.setId(42L);
        p.setProviderPaymentReference("T-REF-42");
        p.setCustomerEmail(CUSTOMER);
        p.setOrder(o);
        return p;
    }

    private void assertOneErrorNaming(String... parts) {
        List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).singleElement().satisfies(e -> {
            for (String part : parts) assertThat(e.getFormattedMessage()).contains(part);
            assertThat(e.getFormattedMessage()).doesNotContain(CUSTOMER);
            assertThat(e.getThrowableProxy()).as("no exception text at ERROR").isNull();
        });
        assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(CUSTOMER));
    }

    @Test
    void customerReceiptSendFailureLogsThePaymentAndOrderReference() {
        DeliveryDetail d = new DeliveryDetail();
        when(addresses.findByUserAndIsDefaultTrue(any())).thenReturn(Optional.of(d));
        service.sendCustomerReceipt(payment());
        assertOneErrorNaming("receipt", "PB-9", "payment 42", "T-REF-42", "MailDeliveryException");
    }

    @Test
    void customerReceiptWithoutADeliveryAddressLogsTheReference() {
        when(addresses.findByUserAndIsDefaultTrue(any())).thenReturn(Optional.empty());
        service.sendCustomerReceipt(payment());
        assertOneErrorNaming("receipt", "PB-9", "payment 42");
    }

    @Test
    void adminPaymentMailFailureLogsTheReference() {
        service.sendAdminOrderNotification(payment());
        assertOneErrorNaming("PB-9", "payment 42", "MailDeliveryException");
    }

    @Test
    void adminNewOrderMailFailureLogsTheOrderNumber() {
        service.notifyAdminOnSuccessfulOrderCreation(payment().getOrder());
        assertOneErrorNaming("PB-9", "MailDeliveryException");
    }

    @Test
    void orderStatusMailFailureLogsTheOrderNumber() {
        service.notifyCustomerOnOrderStatusChange(OrderStatusChangeEvent.builder().orderNumber("PB-9")
                .customerEmail(CUSTOMER).build());
        assertOneErrorNaming("PB-9", "MailDeliveryException");
    }

    // ---- EVT-01 rework 1 (F3): OTP / password reset / status e-mails never log the address, sent or not

    private void assertNoAddressAtAnyLevel(Runnable send) {
        Level old = logger.getLevel();
        logger.setLevel(Level.TRACE);
        try {
            send.run();
        } finally {
            logger.setLevel(old);
        }
        assertThat(appender.list).isNotEmpty().allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain(CUSTOMER).doesNotContain("buyer@");
            assertThat(e.getThrowableProxy()).as("no exception (its message can quote the recipient)").isNull();
            assertThat(e.getArgumentArray() == null ? List.of() : List.of(e.getArgumentArray()))
                    .noneMatch(a -> String.valueOf(a).contains(CUSTOMER));
        });
    }

    @Test
    void otpMailFailureLogsNoAddress() {
        assertNoAddressAtAnyLevel(() -> service.sendOtpEmail(CUSTOMER, "123456"));
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("OTP e-mail").contains("NOT sent"));
    }

    @Test
    void otpMailSuccessLogsNoAddress() {
        doNothing().when(transport).send(any());
        assertNoAddressAtAnyLevel(() -> service.sendOtpEmail(CUSTOMER, "123456"));
    }

    @Test
    void passwordResetMailFailureLogsTheTenantNotTheAddress() {
        assertNoAddressAtAnyLevel(() -> service.sendForgotPasswordEmail(CUSTOMER, "Buyer", "tok-1", "acme"));
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("acme").contains("NOT sent"));
    }

    @Test
    void passwordResetMailSuccessLogsNoAddress() {
        doNothing().when(transport).send(any());
        assertNoAddressAtAnyLevel(() -> service.sendForgotPasswordEmail(CUSTOMER, "Buyer", "tok-1", "acme"));
    }

    @Test
    void orderStatusMailSuccessLogsTheOrderNumberNotTheAddress() {
        doNothing().when(transport).send(any());
        assertNoAddressAtAnyLevel(() -> service.notifyCustomerOnOrderStatusChange(OrderStatusChangeEvent.builder()
                .orderNumber("PB-9").customerEmail(CUSTOMER).build()));
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("PB-9"));
    }

    @Test
    void receiptAndAdminMailFailuresLogNoAddressAtAnyLevel() {
        when(addresses.findByUserAndIsDefaultTrue(any())).thenReturn(Optional.of(new DeliveryDetail()));
        assertNoAddressAtAnyLevel(() -> {
            service.sendCustomerReceipt(payment());
            service.sendAdminOrderNotification(payment());
        });
    }
}
