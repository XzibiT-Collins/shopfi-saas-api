package com.example.perfume_budget.dto.ws;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record WebSocketNotificationBody (
        Long recipientId,
        Long notificationId,
        String type,
        String title,
        String message,
        String referenceType,
        String referenceId,
        boolean read,
        LocalDateTime readAt,
        LocalDateTime deliveredAt,
        LocalDateTime createdAt
){}
