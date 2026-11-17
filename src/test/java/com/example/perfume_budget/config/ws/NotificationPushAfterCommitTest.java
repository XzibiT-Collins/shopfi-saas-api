package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.Notification;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.NotificationRecipientRepository;
import com.example.perfume_budget.repository.NotificationRepository;
import com.example.perfume_budget.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * EVT-01 (a): the realtime STOMP push of an admin / staff notification goes out only once the notification rows it
 * announces are committed: a transaction that rolls back pushes nothing (the admin would otherwise open a
 * notification that does not exist), and a committed one pushes after the commit, not before.
 */
class NotificationPushAfterCommitTest {

    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    private SimpMessageSendingOperations messaging;
    private NotificationService service;
    private final TransactionTemplate tx = new TransactionTemplate(new NoopTxManager());
    private Payment payment;

    @org.junit.jupiter.api.AfterEach
    void clearTenant() {
        com.example.perfume_budget.tenancy.TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        com.example.perfume_budget.tenancy.TenantContext.set(com.example.perfume_budget.tenancy.TenantIdentity.tenant("acme", "tenant_acme"));
        messaging = mock(SimpMessageSendingOperations.class);
        AdminSessionManager sessions = mock(AdminSessionManager.class);
        UserRepository users = mock(UserRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        NotificationRecipientRepository recipients = mock(NotificationRecipientRepository.class);
        service = new NotificationService(messaging, sessions, users, notifications, recipients);
        User admin = User.builder().id(1L).email("admin@shop.test").roles(UserRole.ADMIN).build();
        when(users.findAllByRolesIn(any())).thenReturn(List.of(admin));
        when(notifications.save(any(Notification.class))).thenAnswer(i -> {
            Notification n = i.getArgument(0);
            n.setId(10L);
            return n;
        });
        when(recipients.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(recipients.existsByNotificationTypeAndNotificationReferenceIdAndIsReadFalse(any(), any())).thenReturn(false);
        when(sessions.getConnectedAdmins("acme")).thenReturn(Set.of("admin@shop.test"));
        Order order = Order.builder().orderNumber("PB-1").build();
        payment = Payment.builder().order(order).build();
    }

    @Test
    void rolledBackPaymentNotificationPushesNothing() {
        tx.executeWithoutResult(st -> {
            service.notifyAdminsOfSuccessfulPayment(payment);
            st.setRollbackOnly();
        });
        verifyNoInteractions(messaging);
    }

    @Test
    void rolledBackShopFloorAlertPushesNothing() {
        tx.executeWithoutResult(st -> {
            service.notifyStaffOfShopFloorShortage(5L, "Oud", 1, 9);
            st.setRollbackOnly();
        });
        verifyNoInteractions(messaging);
    }

    @Test
    void committedNotificationsArePushedOnceAfterTheCommit() {
        tx.executeWithoutResult(st -> {
            service.notifyAdminsOfSuccessfulPayment(payment);
            service.notifyStaffOfShopFloorShortage(5L, "Oud", 1, 9);
            verifyNoInteractions(messaging); // not before the commit
        });
        verify(messaging, times(2)).convertAndSendToUser(eq("acme:admin@shop.test"), eq("/queue/notifications"), any());
    }

    @Test
    void outsideATransactionThePushIsImmediate() {
        service.notifyAdminsOfSuccessfulPayment(payment);
        verify(messaging, times(1)).convertAndSendToUser(eq("acme:admin@shop.test"), eq("/queue/notifications"), any());
    }
}
