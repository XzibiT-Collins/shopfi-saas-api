package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    /** EVT-01b: the admin payment notification of an order is written once, however often its outbox row runs. */
    boolean existsByTypeAndReferenceId(String type, String referenceId);
}
