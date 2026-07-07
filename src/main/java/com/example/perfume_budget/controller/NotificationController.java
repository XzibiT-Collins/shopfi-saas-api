package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.notification.NotificationResponse;
import com.example.perfume_budget.dto.notification.UnreadNotificationCountResponse;
import com.example.perfume_budget.service.interfaces.NotificationInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationInboxService notificationInboxService;

    @GetMapping
    public ResponseEntity<CustomApiResponse<PageResponse<NotificationResponse>>> getNotifications(Pageable pageable) {
        return ResponseEntity.ok(CustomApiResponse.success(notificationInboxService.getMyNotifications(pageable)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<CustomApiResponse<UnreadNotificationCountResponse>> getUnreadCount() {
        return ResponseEntity.ok(CustomApiResponse.success(
                new UnreadNotificationCountResponse(notificationInboxService.getUnreadCount()))
        );
    }

    @PatchMapping("/{recipientId}/read")
    public ResponseEntity<CustomApiResponse<NotificationResponse>> markAsRead(@PathVariable Long recipientId) {
        return ResponseEntity.ok(CustomApiResponse.success(notificationInboxService.markAsRead(recipientId)));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead() {
        notificationInboxService.markAllAsRead();
        return ResponseEntity.noContent().build();
    }
}
