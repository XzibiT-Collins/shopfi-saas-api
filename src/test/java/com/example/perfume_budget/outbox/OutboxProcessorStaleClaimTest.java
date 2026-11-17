package com.example.perfume_budget.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EVT-01c (O2): a worker whose claim went stale before it could mark the row DONE says so (another worker owns it). */
class OutboxProcessorStaleClaimTest {

    private final OutboxStore store = mock(OutboxStore.class);
    private final EmailService email = mock(EmailService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private OutboxProcessor processor;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        processor = new OutboxProcessor(store, email, mock(NotificationService.class), mock(PaymentRepository.class), orders, tm);
        Order order = new Order();
        order.setId(17L);
        when(orders.findById(17L)).thenReturn(Optional.of(order));
        logger = (Logger) LoggerFactory.getLogger(OutboxProcessor.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void aSentRowWhoseClaimWentStaleIsReportedNotSilentlyLost() {
        OutboxStore.Row row = new OutboxStore.Row(5L, OutboxKind.NEW_ORDER_ADMIN_MAIL, "17", 1);
        when(store.complete(row)).thenReturn(false);
        processor.execute(row);
        verify(email).notifyAdminOnSuccessfulOrderCreation(any());
        verify(store).complete(row);
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("went stale").contains("order 17").contains("attempt 1");
        });
    }

    @Test
    void aCompletedRowLogsNothing() {
        OutboxStore.Row row = new OutboxStore.Row(5L, OutboxKind.NEW_ORDER_ADMIN_MAIL, "17", 1);
        when(store.complete(row)).thenReturn(true);
        processor.execute(row);
        assertThat(logs.list).isEmpty();
    }
}
