package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
