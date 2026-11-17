package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * EVT-01c (O5): writes the admin payment notification of an order at most once, race-free: the tenant's unique
     * partial index {@code uk_notifications_payment_success_reference} (V15) on {@code (type, reference_id) WHERE
     * type = 'PAYMENT_SUCCESS'} decides between concurrent writers (a retried outbox row, a worker whose lease ran
     * out), and the conflict check is an index probe, not a scan.
     *
     * @return the new notification's id, or empty if the order already has its payment notification
     */
    @Transactional
    @Query(nativeQuery = true, value = "INSERT INTO notifications (type, title, message, reference_type, reference_id, created_at)"
            + " VALUES ('PAYMENT_SUCCESS', :title, :message, 'ORDER', :orderNumber, :createdAt)"
            + " ON CONFLICT (type, reference_id) WHERE type = 'PAYMENT_SUCCESS' DO NOTHING RETURNING id")
    List<Long> insertPaymentSuccessOnce(@Param("title") String title, @Param("message") String message,
                                        @Param("orderNumber") String orderNumber, @Param("createdAt") LocalDateTime createdAt);
}
