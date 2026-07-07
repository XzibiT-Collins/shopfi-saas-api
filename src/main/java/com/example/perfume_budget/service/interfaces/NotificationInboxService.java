package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.notification.NotificationResponse;
import org.springframework.data.domain.Pageable;

public interface NotificationInboxService {
    PageResponse<NotificationResponse> getMyNotifications(Pageable pageable);

    long getUnreadCount();

    NotificationResponse markAsRead(Long recipientId);

    void markAllAsRead();
}
