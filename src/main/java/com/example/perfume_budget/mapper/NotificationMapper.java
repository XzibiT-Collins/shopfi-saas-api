package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.notification.NotificationResponse;
import com.example.perfume_budget.dto.ws.WebSocketNotificationBody;
import com.example.perfume_budget.model.NotificationRecipient;

public class NotificationMapper {

    private NotificationMapper() {
        throw new IllegalStateException("Utility class");
    }

    public static NotificationResponse toResponse(NotificationRecipient recipient) {
        return NotificationResponse.builder()
                .recipientId(recipient.getId())
                .notificationId(recipient.getNotification().getId())
                .type(recipient.getNotification().getType())
                .title(recipient.getNotification().getTitle())
                .message(recipient.getNotification().getMessage())
                .referenceType(recipient.getNotification().getReferenceType())
                .referenceId(recipient.getNotification().getReferenceId())
                .read(recipient.isRead())
                .readAt(recipient.getReadAt())
                .deliveredAt(recipient.getDeliveredAt())
                .createdAt(recipient.getNotification().getCreatedAt())
                .build();
    }

    public static WebSocketNotificationBody toWebSocketBody(NotificationRecipient recipient) {
        NotificationResponse response = toResponse(recipient);
        return WebSocketNotificationBody.builder()
                .recipientId(response.recipientId())
                .notificationId(response.notificationId())
                .type(response.type())
                .title(response.title())
                .message(response.message())
                .referenceType(response.referenceType())
                .referenceId(response.referenceId())
                .read(response.read())
                .readAt(response.readAt())
                .deliveredAt(response.deliveredAt())
                .createdAt(response.createdAt())
                .build();
    }
}
