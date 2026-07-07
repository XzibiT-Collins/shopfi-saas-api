package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.notification.NotificationResponse;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.exception.UnauthorizedException;
import com.example.perfume_budget.mapper.NotificationMapper;
import com.example.perfume_budget.model.NotificationRecipient;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.NotificationRecipientRepository;
import com.example.perfume_budget.service.interfaces.NotificationInboxService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationInboxServiceImpl implements NotificationInboxService {
    private final NotificationRecipientRepository notificationRecipientRepository;
    private final AuthUserUtil authUserUtil;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(Pageable pageable) {
        User currentUser = requireCurrentUser();
        Page<NotificationResponse> page = notificationRecipientRepository.findByUserOrderByCreatedAtDesc(currentUser, pageable)
                .map(NotificationMapper::toResponse);
        return PaginationUtil.createPageResponse(page);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount() {
        return notificationRecipientRepository.countByUserAndIsReadFalse(requireCurrentUser());
    }

    @Override
    public NotificationResponse markAsRead(Long recipientId) {
        User currentUser = requireCurrentUser();
        NotificationRecipient recipient = notificationRecipientRepository.findByIdAndUser(recipientId, currentUser)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found."));

        if (!recipient.isRead()) {
            recipient.setRead(true);
            recipient.setReadAt(LocalDateTime.now());
        }

        return NotificationMapper.toResponse(recipientRepositorySave(recipient));
    }

    @Override
    public void markAllAsRead() {
        User currentUser = requireCurrentUser();
        List<NotificationRecipient> unreadNotifications = notificationRecipientRepository.findByUserAndIsReadFalse(currentUser);
        if (unreadNotifications.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        unreadNotifications.forEach(recipient -> {
            recipient.setRead(true);
            recipient.setReadAt(now);
        });
        notificationRecipientRepository.saveAll(unreadNotifications);
    }

    private User requireCurrentUser() {
        User currentUser = authUserUtil.getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedException("Authenticated user is required.");
        }
        return currentUser;
    }

    private NotificationRecipient recipientRepositorySave(NotificationRecipient recipient) {
        return notificationRecipientRepository.save(recipient);
    }
}
