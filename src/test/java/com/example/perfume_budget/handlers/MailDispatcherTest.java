package com.example.perfume_budget.handlers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * EVT-01 (d): receipt / e-mail work that is lost after the commit (the mail executor rejected it, or the re-read /
 * render / send threw) is logged at ERROR with the order / payment reference, and nothing else that identifies the
 * customer (no e-mail address, no OTP, no exception message, which can carry the recipient).
 */
class MailDispatcherTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(MailDispatcher.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
    }

    private List<String> errors() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static Payment payment() {
        Payment p = new Payment();
        p.setId(42L);
        p.setProviderPaymentReference("T-REF-42");
        p.setCustomerEmail("buyer@shop.test");
        return p;
    }

    @Test
    void runsTheWorkOnTheExecutor() {
        AtomicInteger ran = new AtomicInteger();
        new MailDispatcher(Runnable::run).submit("customer receipt", "payment 42", ran::incrementAndGet);
        assertThat(ran).hasValue(1);
        assertThat(errors()).isEmpty();
    }

    @Test
    void rejectedWorkIsLoggedWithItsReferenceAndDoesNotThrow() {
        MailDispatcher d = new MailDispatcher(r -> { throw new TaskRejectedException("pool full"); });
        assertThatCode(() -> d.submit("customer receipt", MailDispatcher.reference(payment()), () -> { }))
                .doesNotThrowAnyException();
        assertThat(errors()).singleElement().satisfies(m -> assertThat(m)
                .contains("customer receipt").contains("payment 42").contains("T-REF-42").contains("rejected")
                .doesNotContain("buyer@shop.test"));
    }

    @Test
    void failingWorkIsLoggedWithItsReferenceButNotTheExceptionMessage() {
        MailDispatcher d = new MailDispatcher(Runnable::run);
        assertThatCode(() -> d.submit("admin new-order e-mail", "order PB-9", () -> {
            throw new IllegalStateException("could not deliver to buyer@shop.test");
        })).doesNotThrowAnyException();
        assertThat(errors()).singleElement().satisfies(m -> assertThat(m)
                .contains("admin new-order e-mail").contains("order PB-9").contains("IllegalStateException")
                .doesNotContain("buyer@shop.test"));
        assertThat(appender.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    @Test
    void referencesNameOnlyIdsNeverCustomerDataOrSecrets() {
        assertThat(MailDispatcher.reference(payment())).isEqualTo("payment 42 (ref T-REF-42)");
        Order o = new Order();
        o.setId(9L);
        o.setOrderNumber("PB-9");
        assertThat(MailDispatcher.reference(o)).isEqualTo("order PB-9");
        assertThat(MailDispatcher.reference(OrderStatusChangeEvent.builder().orderNumber("PB-9")
                .customerEmail("buyer@shop.test").build())).isEqualTo("order PB-9");
        assertThat(MailDispatcher.reference(new UserRegistrationEvent("buyer@shop.test", "123456")))
                .isEqualTo("UserRegistrationEvent");
        assertThat(MailDispatcher.reference(new ViewCountEvent(5L))).isEqualTo("ViewCountEvent");
        assertThat(MailDispatcher.reference(null)).isEqualTo("null");
    }
}
