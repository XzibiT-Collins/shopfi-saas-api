package com.example.perfume_budget.handlers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.notification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * EVT-01 rework 1 (F3, F4 R14 / R17): each e-mail listener only HANDS its send to {@link MailDispatcher} (nothing is
 * sent on the publishing thread, which is still inside its transaction's after-commit callback); the reference it
 * hands over (logged if the mail is lost) and everything the listener / its work logs carry no e-mail address.
 */
class MailListenerHandOffTest {

    private static final String ADDRESS = "buyer@shop.test";

    private EmailService email;
    private MailDispatcher dispatcher;
    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();
    private final List<Logger> loggers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        email = mock(EmailService.class);
        dispatcher = mock(MailDispatcher.class);
        for (Class<?> c : List.of(OTPEmailSendHandler.class, ForgotPasswordHandler.class, OrderStatusHandler.class)) {
            Logger l = (Logger) LoggerFactory.getLogger(c);
            ListAppender<ILoggingEvent> a = new ListAppender<>();
            a.start();
            l.addAppender(a);
            l.setLevel(Level.TRACE);
            loggers.add(l);
            appenders.add(a);
        }
    }

    @AfterEach
    void tearDown() {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).detachAppender(appenders.get(i));
            loggers.get(i).setLevel(null);
        }
    }

    /** The work the listener handed off, after checking nothing was sent before the hand-off ran. */
    private Runnable handedOff(String expectedWhatPart) {
        ArgumentCaptor<String> what = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
        verify(dispatcher).submit(what.capture(), reference.capture(), work.capture());
        assertThat(what.getValue()).contains(expectedWhatPart);
        assertThat(reference.getValue()).as("the loss reference names no address").doesNotContain(ADDRESS).doesNotContain("@");
        verifyNoInteractions(email);
        return work.getValue();
    }

    private void assertNoAddressLogged() {
        for (ListAppender<ILoggingEvent> a : appenders) {
            assertThat(a.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(ADDRESS).doesNotContain("@"));
        }
    }

    @Test
    void registrationOtpIsHandedOffNotSentInline() {
        new OTPEmailSendHandler(email, dispatcher).handleUserRegistrationOtpEvent(new UserRegistrationEvent(ADDRESS, "123456"));
        handedOff("registration OTP").run();
        verify(email).sendOtpEmail(ADDRESS, "123456");
        assertNoAddressLogged();
        assertThat(appenders.get(0).list).isNotEmpty();
    }

    @Test
    void loginOtpIsHandedOffNotSentInline() {
        new OTPEmailSendHandler(email, dispatcher).handleLoginOtpEvent(new LoginOtpEvent(ADDRESS, "654321"));
        handedOff("login OTP").run();
        verify(email).sendOtpEmail(ADDRESS, "654321");
        assertNoAddressLogged();
    }

    @Test
    void passwordResetIsHandedOffNotSentInline() {
        new ForgotPasswordHandler(email, dispatcher).handlePasswordResetEvent(new ResetPasswordEvent(ADDRESS, "B", "tok", "acme"));
        handedOff("password reset").run();
        verify(email).sendForgotPasswordEmail(ADDRESS, "B", "tok", "acme");
        assertNoAddressLogged();
        assertThat(appenders.get(1).list).isNotEmpty();
    }

    @Test
    void orderStatusMailIsHandedOffWithAReferenceThatHasNoAddress() {
        OrderStatusChangeEvent e = OrderStatusChangeEvent.builder().orderNumber("PB-77").customerEmail(ADDRESS)
                .status(OrderProcessingStatus.PACKING).build();
        new OrderStatusHandler(email, dispatcher).handleOrderStatusChangeEvent(e);
        verify(dispatcher).submit(anyString(), eq("order PB-77"), any());
        handedOff("order status").run();
        verify(email).notifyCustomerOnOrderStatusChange(e);
        assertNoAddressLogged();
    }
}
