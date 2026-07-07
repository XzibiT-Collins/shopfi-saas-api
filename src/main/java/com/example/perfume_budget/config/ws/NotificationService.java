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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
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

    @Transactional
    public void notifyAdminsOfSuccessfulPayment(Payment payment) {
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

        Set<String> connectedAdmins = adminSessionManager.getConnectedAdmins();
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
            messagingTemplate.convertAndSendToUser(
                    adminUsername,
                    "/queue/notifications",
                    NotificationMapper.toWebSocketBody(recipient)
            );
            log.info("Sent payment success notification to admin: {}", adminUsername);
            deliveredRecipients.add(recipient);
        }

        if (!deliveredRecipients.isEmpty()) {
            notificationRecipientRepository.saveAll(deliveredRecipients);
        }
    }

    @Transactional
    public void notifyStaffOfShopFloorShortage(Long productId, String productName, int floorQuantity, int storeRoomQuantity) {
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

        Set<String> connectedAdmins = adminSessionManager.getConnectedAdmins();
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
            messagingTemplate.convertAndSendToUser(
                    staffUsername,
                    "/queue/notifications",
                    NotificationMapper.toWebSocketBody(recipient)
            );
            deliveredRecipients.add(recipient);
        }

        if (!deliveredRecipients.isEmpty()) {
            notificationRecipientRepository.saveAll(deliveredRecipients);
        }
    }
}
