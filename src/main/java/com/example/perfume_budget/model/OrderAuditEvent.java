package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.OrderAuditEventType;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Append-only audit row for an admin action on an order (ORD-01). Every column is non-updatable and the application
 * has no update/delete path; the only personal data is the acting admin's e-mail.
 */
@Entity
@Table(name = "order_audit_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    private OrderAuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 32)
    private OrderProcessingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", updatable = false, length = 32)
    private OrderProcessingStatus toStatus;

    @Column(name = "refund_required", nullable = false, updatable = false)
    private boolean refundRequired;

    @Column(name = "actor_email", nullable = false, updatable = false)
    private String actorEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
