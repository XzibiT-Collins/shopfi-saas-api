package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.mapper.NotificationMapper;
import com.example.perfume_budget.model.Notification;
import com.example.perfume_budget.model.NotificationRecipient;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.NotificationRecipientRepository;
import com.example.perfume_budget.repository.NotificationRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.utils.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final SimpMessageSendingOperations messagingTemplate;
    private final AdminSessionManager adminSessionManager;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository notificationRecipientRepository;

    /**
     * Rework 1: always its own transaction (REQUIRES_NEW): called from after-commit work, a REQUIRED transaction would
     * join a publishing transaction that has already committed (rows never written, push never sent).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyAdminsOfSuccessfulPayment(Payment payment) {
        String tenant = TenantContext.getSlug();
        List<User> adminUsers = userRepository.findAllByRolesIn(List.of(UserRole.ADMIN, UserRole.FRONT_DESK));
        if (adminUsers.isEmpty()) {
            log.info("No admin users found to persist notification for order {}", payment.getOrder().getOrderNumber());
            return;
        }

        Notification notification = notificationRepository.save(
                Notification.builder()
                        .type("PAYMENT_SUCCESS")
                        .title("Payment received")
                        .message("Order #" + payment.getOrder().getOrderNumber() + " has been successfully paid.")
                        .referenceType("ORDER")
                        .referenceId(payment.getOrder().getOrderNumber())
                        .build()
        );

        List<NotificationRecipient> recipients = new ArrayList<>();
        for (User adminUser : adminUsers) {
            recipients.add(NotificationRecipient.builder()
                    .notification(notification)
                    .user(adminUser)
                    .isRead(false)
                    .build());
        }
        List<NotificationRecipient> savedRecipients = notificationRecipientRepository.saveAll(recipients);

        log.info("Persisted payment success notification {} for {} admin recipients.", notification.getId(), savedRecipients.size());

        Set<String> connectedAdmins = adminSessionManager.getConnectedAdmins(tenant);
        if (connectedAdmins.isEmpty()) {
            log.info("No admins connected to receive realtime notification.");
            return;
        }

        log.info("Sending payment success notification to {} connected admins via user-specific queues.", connectedAdmins.size());

        LocalDateTime deliveredAt = LocalDateTime.now();
        List<NotificationRecipient> deliveredRecipients = new ArrayList<>();
        for (NotificationRecipient recipient : savedRecipients) {
            String adminUsername = recipient.getUser().getEmail();
            if (!connectedAdmins.contains(adminUsername)) {
                continue;
            }

            recipient.setDeliveredAt(deliveredAt);
            pushAfterCommit(tenant, adminUsername, NotificationMapper.toWebSocketBody(recipient));
            log.info("Sent payment success notification to admin user {}", recipient.getUser().getId());
            deliveredRecipients.add(recipient);
        }

        if (!deliveredRecipients.isEmpty()) {
            notificationRecipientRepository.saveAll(deliveredRecipients);
        }
    }

    /**
     * Rework 1: always its own transaction (REQUIRES_NEW): called from after-commit work, a REQUIRED transaction would
     * join a publishing transaction that has already committed (rows never written, push never sent).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyStaffOfShopFloorShortage(Long productId, String productName, int floorQuantity, int storeRoomQuantity) {
        String tenant = TenantContext.getSlug();
        // Throttle: one open alert per product until someone reads it.
        if (notificationRecipientRepository.existsByNotificationTypeAndNotificationReferenceIdAndIsReadFalse(
                "SHOP_FLOOR_RESTOCK", productId.toString())) {
            log.info("Unread shop floor restock alert already exists for product {}; skipping.", productId);
            return;
        }

        List<User> staffUsers = userRepository.findAllByRolesIn(List.of(UserRole.ADMIN, UserRole.FRONT_DESK));
        if (staffUsers.isEmpty()) {
            log.info("No staff users found to persist shop floor restock notification for product {}", productId);
            return;
        }

        Notification notification = notificationRepository.save(
                Notification.builder()
                        .type("SHOP_FLOOR_RESTOCK")
                        .title("Shop floor restock needed")
                        .message(productName + " is low on the shop floor (" + floorQuantity
                                + " left). " + storeRoomQuantity + " unit(s) available in store rooms.")
                        .referenceType("PRODUCT")
                        .referenceId(productId.toString())
                        .build()
        );

        List<NotificationRecipient> recipients = new ArrayList<>();
        for (User staffUser : staffUsers) {
            recipients.add(NotificationRecipient.builder()
                    .notification(notification)
                    .user(staffUser)
                    .isRead(false)
                    .build());
        }
        List<NotificationRecipient> savedRecipients = notificationRecipientRepository.saveAll(recipients);

        log.info("Persisted shop floor restock notification {} for {} staff recipients.", notification.getId(), savedRecipients.size());

        Set<String> connectedAdmins = adminSessionManager.getConnectedAdmins(tenant);
        if (connectedAdmins.isEmpty()) {
            return;
        }

        LocalDateTime deliveredAt = LocalDateTime.now();
        List<NotificationRecipient> deliveredRecipients = new ArrayList<>();
        for (NotificationRecipient recipient : savedRecipients) {
            String staffUsername = recipient.getUser().getEmail();
            if (!connectedAdmins.contains(staffUsername)) {
                continue;
            }

            recipient.setDeliveredAt(deliveredAt);
            pushAfterCommit(tenant, staffUsername, NotificationMapper.toWebSocketBody(recipient));
            deliveredRecipients.add(recipient);
        }

        if (!deliveredRecipients.isEmpty()) {
            notificationRecipientRepository.saveAll(deliveredRecipients);
        }
    }

    /**
     * EVT-01: the realtime push announces notification rows this transaction writes, so it goes out only after they
     * commit (never for a rolled-back write); the body is built now, while the rows are at hand.
     * SEC-03c: addressed to the tenant-qualified user name ({@link StompTenantAuthentication#userName}), so only
     * sessions of this tenant can receive it. The tenant is captured before the commit, never read afterwards.
     */
    private void pushAfterCommit(String tenant, String email, Object body) {
        String user = StompTenantAuthentication.userName(tenant, email);
        AfterCommit.run(() -> messagingTemplate.convertAndSendToUser(user, "/queue/notifications", body));
    }
}
