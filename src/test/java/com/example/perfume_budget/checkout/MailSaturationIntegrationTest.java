package com.example.perfume_budget.checkout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.config.AsyncConfig;
import com.example.perfume_budget.config.TrackedTaskExecutor;
import com.example.perfume_budget.config.ws.AdminSessionManager;
import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.notification.MailMessage;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.InterceptableChannel;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * EVT-01 rework 1 (F1) / EVT-01b, on the real stack: what happens when the mail executor is FULL (every mail thread
 * busy, every queue slot taken), deterministically, by filling the real {@code mailTaskExecutor} bean.
 *
 * <ul>
 *   <li>The admin in-app notification of a settled payment is still committed (rows) and pushed (STOMP), from its
 *       own executor in its own transaction. The receipt is NOT handed off (refused at once, no wait, EVT-01b L1):
 *       its outbox row stays PENDING and the outbox drain sends it, once, when the executor has room (L2).</li>
 *   <li>The receipt is sent on a mail thread with no transaction and no connection bound.</li>
 *   <li>A hung SMTP server does not delay a checkout, and the request thread does not keep a database connection
 *       afterwards.</li>
 * </ul>
 */
class MailSaturationIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired @Qualifier(AsyncConfig.MAIL_EXECUTOR) private TaskExecutor mailBean;
    @Autowired @Qualifier("brokerChannel") private MessageChannel brokerChannel;
    @Autowired private AdminSessionManager sessions;
    @Autowired private DataSource dataSource;
    @Autowired private org.springframework.mail.javamail.JavaMailSender javaMailSender;
    @Autowired private com.example.perfume_budget.outbox.OutboxDrainer drainer;

    private TrackedTaskExecutor mail;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> pushedTo = new CopyOnWriteArrayList<>();
    private final ChannelInterceptor capture = new ChannelInterceptor() {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            String d = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            if (d != null && d.endsWith("/queue/notifications")) {
                pushedTo.add(d);
            }
            return message;
        }
    };
    private ListAppender<ILoggingEvent> dispatcherLog;
    private Logger dispatcherLogger;

    @BeforeEach
    void setUp() {
        mail = (TrackedTaskExecutor) mailBean;
        ((InterceptableChannel) brokerChannel).addInterceptor(capture);
        sessions.addAdmin(acme.adminEmail());
        dispatcherLogger = (Logger) LoggerFactory.getLogger(MailDispatcher.class);
        dispatcherLog = new ListAppender<>();
        dispatcherLog.start();
        dispatcherLogger.addAppender(dispatcherLog);
    }

    @AfterEach
    void tearDown() {
        release.countDown(); // free the mail executor before the base class waits for it
        ((InterceptableChannel) brokerChannel).removeInterceptor(capture);
        sessions.removeAdmin(acme.adminEmail());
        dispatcherLogger.detachAppender(dispatcherLog);
    }

    /** Occupies every mail thread (until {@link #release}) and every queue slot. */
    private void saturateMail(Runnable onEachThread) throws InterruptedException {
        int threads = mail.getMaxPoolSize();
        CountDownLatch started = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            mail.execute(() -> {
                started.countDown();
                onEachThread.run();
            });
        }
        assertThat(started.await(10, TimeUnit.SECONDS)).as("all mail threads busy").isTrue();
        for (int i = 0; i < mail.getQueueCapacity(); i++) {
            mail.execute(() -> { });
        }
        assertThat(mail.getThreadPoolExecutor().getQueue().remainingCapacity()).as("mail queue full").isZero();
    }

    private void blockUntilReleased() {
        try {
            release.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String place(Customer c) throws Exception {
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private String reference(String orderNumber) {
        return jdbc.queryForObject("SELECT p.provider_payment_reference FROM tenant_acme.payments p JOIN tenant_acme.orders o "
                + "ON o.id = p.order_id WHERE o.order_number = ?", String.class, orderNumber);
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, orderNumber).longValue();
    }

    private int notifications(String orderNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.notifications WHERE type = 'PAYMENT_SUCCESS' "
                + "AND reference_id = ?", Integer.class, orderNumber);
    }

    private int deliveredRecipients(String orderNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.notification_recipients r JOIN tenant_acme.notifications n "
                + "ON n.id = r.notification_id WHERE n.reference_id = ? AND r.delivered_at IS NOT NULL", Integer.class, orderNumber);
    }

    private int mails(String subjectPart) {
        return (int) Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (MailMessage) i.getArgument(0))
                .filter(m -> m.subject() != null && m.subject().contains(subjectPart))
                .count();
    }

    private List<ILoggingEvent> notSent(String referencePart) {
        return dispatcherLog.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("NOT sent") && e.getFormattedMessage().contains(referencePart))
                .toList();
    }

    private List<ILoggingEvent> deferred(String referencePart) {
        return dispatcherLog.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("not handed off") && e.getFormattedMessage().contains(referencePart))
                .toList();
    }

    private long paymentId(String orderNumber) {
        return jdbc.queryForObject("SELECT p.id FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id "
                + "WHERE o.order_number = ?", Long.class, orderNumber);
    }

    private long orderId(String orderNumber) {
        return jdbc.queryForObject("SELECT id FROM tenant_acme.orders WHERE order_number = ?", Long.class, orderNumber);
    }

    private String outboxStatus(String kind, long reference) {
        return jdbc.queryForObject("SELECT status || '/' || attempts FROM tenant_acme.side_effect_outbox WHERE kind = ? AND reference = ?",
                String.class, kind, String.valueOf(reference));
    }

    /** Lets the outbox drain see every pending row now (instead of after the first hand-off grace). */
    private void drainNow() {
        jdbc.update("UPDATE tenant_acme.side_effect_outbox SET next_attempt_at = now() WHERE status = 'PENDING'");
        drainer.drainAllTenants();
    }

    private int activeConnections() throws Exception {
        return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
    }

    @Test
    void mailExecutorFull_notificationIsCommittedAndPushed_receiptDeferredToTheOutboxAndSentOnceLater() throws Exception {
        Customer c = customer(acme, "sat-1@acme.test");
        cart(c, product(acme, "P-SAT-1", "100.00", 20), 1);
        String order = place(c);
        String ref = reference(order);
        Gateway g = configureGateway(acme);
        awaitBackgroundIdle();
        saturateMail(this::blockUntilReleased);

        assertThat(webhook(g, event("charge.success", ref, totalMinor(order)))).isEqualTo(200);
        long payment = paymentId(order);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(notifications(order)).as("notification rows committed").isEqualTo(1);
            assertThat(pushedTo).as("STOMP push sent after the notification commit")
                    .anySatisfy(d -> assertThat(d).contains(acme.adminEmail()));
        });
        assertThat(deliveredRecipients(order)).as("the delivered mark committed too").isEqualTo(1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(deferred("payment " + payment)).hasSize(2)
                .allSatisfy(e -> {
                    assertThat(e.getFormattedMessage()).contains("queue full").contains("outbox retries");
                    assertThat(e.getThreadName()).as("refused at once on the webhook worker").startsWith("webhook-");
                }));
        assertThat(notSent("payment " + payment)).as("not a loss any more").isEmpty();
        assertThat(outboxStatus("PAYMENT_RECEIPT", payment)).isEqualTo("PENDING/0");
        assertThat(outboxStatus("PAYMENT_ADMIN_MAIL", payment)).isEqualTo("PENDING/0");
        assertThat(outboxStatus("PAYMENT_ADMIN_NOTIFICATION", payment)).isEqualTo("DONE/1");

        release.countDown();
        awaitBackgroundIdle();
        assertThat(mails("Your Order Receipt - " + order)).as("nothing sent on the caller or by itself").isZero();
        drainNow();
        awaitBackgroundIdle();
        assertThat(mails("Your Order Receipt - " + order)).as("the drain sent the receipt").isEqualTo(1);
        assertThat(mails("Payment successful for order #" + order)).isEqualTo(1);
        assertThat(outboxStatus("PAYMENT_RECEIPT", payment)).isEqualTo("DONE/1");
        drainNow();
        awaitBackgroundIdle();
        assertThat(mails("Your Order Receipt - " + order)).as("exactly once").isEqualTo(1);
        assertThat(notifications(order)).isEqualTo(1);
    }

    private record Seen(String thread, boolean nothingBound, boolean txActive, boolean syncActive) {}

    @Test
    void theReceiptIsSentOnAMailThreadWithNoTransactionOrConnectionBound() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            MailMessage m = inv.getArgument(0);
            if (m.subject() != null && m.subject().startsWith("Your Order Receipt")) {
                seen.add(new Seen(Thread.currentThread().getName(), TransactionSynchronizationManager.getResourceMap().isEmpty(),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isSynchronizationActive()));
            }
            return null;
        }).when(mailTransport).send(any());
        Customer c = customer(acme, "sat-2@acme.test");
        cart(c, product(acme, "P-SAT-2", "100.00", 20), 1);
        String order = place(c);
        String ref = reference(order);
        Gateway g = configureGateway(acme);
        awaitBackgroundIdle();

        assertThat(webhook(g, event("charge.success", ref, totalMinor(order)))).isEqualTo(200);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(seen).hasSize(1));
        assertThat(seen.getFirst().thread()).startsWith("mail-");
        assertThat(seen.getFirst().nothingBound()).as("no EntityManager / connection bound while sending").isTrue();
        assertThat(seen.getFirst().txActive()).isFalse();
        assertThat(seen.getFirst().syncActive()).isFalse();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(notifications(order)).isEqualTo(1));
        awaitBackgroundIdle();
        assertThat(outboxStatus("PAYMENT_RECEIPT", paymentId(order))).isEqualTo("DONE/1");
        assertThat(notSent(ref)).isEmpty();
    }

    @Test
    void aHungSmtpServerDoesNotDelayACheckoutAndHoldsNoConnection() throws Exception {
        doAnswer(inv -> {
            blockUntilReleased(); // SMTP accepted the connection and never answers
            return null;
        }).when(mailTransport).send(any());
        // every mail thread is stuck in a real (mock) SMTP send, and the queue is full behind them
        saturateMail(() -> mailTransport.send(new MailMessage("a@shop.test", "b@shop.test", "hung", "<p/>")));

        for (int i = 0; i < 3; i++) {
            Customer c = customer(acme, "sat-h" + i + "@acme.test");
            cart(c, product(acme, "P-SAT-H" + i, "100.00", 20), 1);
            String order = place(c);
            long id = orderId(order);
            assertThat(deferred("order " + id)).as("its admin new-order mail was deferred to the outbox, not waited for")
                    .singleElement().satisfies(e -> {
                        assertThat(e.getFormattedMessage()).contains("queue full");
                        assertThat(e.getThreadName()).doesNotStartWith("mail-");
                    });
            assertThat(outboxStatus("NEW_ORDER_ADMIN_MAIL", id)).isEqualTo("PENDING/0");
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(activeConnections()).as("no request thread kept a connection").isZero());
    }

    /** F1: every SMTP step is bounded (JavaMail's defaults are infinite), as configured in application.properties. */
    @Test
    void theSmtpClientHasConnectReadAndWriteTimeouts() {
        java.util.Properties p = ((org.springframework.mail.javamail.JavaMailSenderImpl) javaMailSender).getJavaMailProperties();
        for (String key : List.of("mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout")) {
            assertThat(p.getProperty(key)).as(key).isNotBlank();
            assertThat(Long.parseLong(p.getProperty(key))).as(key).isBetween(1_000L, 30_000L);
        }
    }
}
