package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.WalkInAuditEventType;
import com.example.perfume_budget.enums.WalkInOrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only audit row of a walk-in cancel / refund (API-RD-03 AC3); V19 rejects UPDATE and DELETE with a trigger.
 * The only personal data is the acting admin's e-mail (same as order_audit_events); the reason is free text typed by
 * the admin and is never logged.
 */
@Entity
@Table(name = "walk_in_order_audit_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalkInOrderAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "walk_in_order_id", nullable = false, updatable = false)
    private Long walkInOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    private WalkInAuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 32)
    private WalkInOrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 32)
    private WalkInOrderStatus toStatus;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_email", nullable = false, updatable = false)
    private String actorEmail;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "refund_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
