package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.NotificationRecipient;
import com.example.perfume_budget.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {
    Page<NotificationRecipient> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    long countByUserAndIsReadFalse(User user);

    Optional<NotificationRecipient> findByIdAndUser(Long id, User user);

    List<NotificationRecipient> findByUserAndIsReadFalse(User user);

    boolean existsByNotificationTypeAndNotificationReferenceIdAndIsReadFalse(String type, String referenceId);
}
