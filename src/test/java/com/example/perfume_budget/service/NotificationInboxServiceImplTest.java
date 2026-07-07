package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.notification.NotificationResponse;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Notification;
import com.example.perfume_budget.model.NotificationRecipient;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.NotificationRecipientRepository;
import com.example.perfume_budget.utils.AuthUserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationInboxServiceImplTest {

    @Mock
    private NotificationRecipientRepository notificationRecipientRepository;

    @Mock
    private AuthUserUtil authUserUtil;

    @InjectMocks
    private NotificationInboxServiceImpl notificationInboxService;

    private User adminUser;
    private NotificationRecipient unreadRecipient;

    @BeforeEach
    void setUp() {
        adminUser = User.builder()
                .id(1L)
                .email("admin@example.com")
                .fullName("Admin User")
                .build();

        Notification notification = Notification.builder()
                .id(10L)
                .type("PAYMENT_SUCCESS")
                .title("Payment received")
                .message("Order #ORD-123 has been successfully paid.")
                .referenceType("ORDER")
                .referenceId("ORD-123")
                .createdAt(LocalDateTime.of(2026, 4, 4, 12, 0))
                .build();

        unreadRecipient = NotificationRecipient.builder()
                .id(100L)
                .notification(notification)
                .user(adminUser)
                .isRead(false)
                .createdAt(LocalDateTime.of(2026, 4, 4, 12, 0))
                .build();
    }

    @Test
    void getMyNotifications_ReturnsMappedPage() {
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(notificationRecipientRepository.findByUserOrderByCreatedAtDesc(adminUser, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(unreadRecipient)));

        PageResponse<NotificationResponse> result = notificationInboxService.getMyNotifications(Pageable.unpaged());

        assertEquals(1, result.content().size());
        assertEquals(100L, result.content().getFirst().recipientId());
        assertEquals("PAYMENT_SUCCESS", result.content().getFirst().type());
    }

    @Test
    void getUnreadCount_ReturnsRepositoryCount() {
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(notificationRecipientRepository.countByUserAndIsReadFalse(adminUser)).thenReturn(3L);

        long result = notificationInboxService.getUnreadCount();

        assertEquals(3L, result);
    }

    @Test
    void markAsRead_UpdatesUnreadNotification() {
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(notificationRecipientRepository.findByIdAndUser(100L, adminUser)).thenReturn(Optional.of(unreadRecipient));
        when(notificationRecipientRepository.save(any(NotificationRecipient.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationResponse response = notificationInboxService.markAsRead(100L);

        assertNotNull(response.readAt());
        assertEquals(true, response.read());
        verify(notificationRecipientRepository).save(unreadRecipient);
    }

    @Test
    void markAsRead_ThrowsWhenNotificationDoesNotBelongToUser() {
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(notificationRecipientRepository.findByIdAndUser(100L, adminUser)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> notificationInboxService.markAsRead(100L));
        verify(notificationRecipientRepository, never()).save(any(NotificationRecipient.class));
    }

    @Test
    void markAllAsRead_UpdatesAllUnreadNotifications() {
        NotificationRecipient secondRecipient = NotificationRecipient.builder()
                .id(101L)
                .notification(unreadRecipient.getNotification())
                .user(adminUser)
                .isRead(false)
                .createdAt(LocalDateTime.of(2026, 4, 4, 12, 5))
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(notificationRecipientRepository.findByUserAndIsReadFalse(adminUser))
                .thenReturn(List.of(unreadRecipient, secondRecipient));

        notificationInboxService.markAllAsRead();

        assertEquals(true, unreadRecipient.isRead());
        assertEquals(true, secondRecipient.isRead());
        verify(notificationRecipientRepository).saveAll(List.of(unreadRecipient, secondRecipient));
    }
}
