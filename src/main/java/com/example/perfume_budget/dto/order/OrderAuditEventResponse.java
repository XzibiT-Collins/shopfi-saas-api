package com.example.perfume_budget.dto.order;

import com.example.perfume_budget.enums.OrderAuditEventType;
import com.example.perfume_budget.enums.OrderProcessingStatus;

import java.time.OffsetDateTime;

/**
 * ORD-02: one row of an order's append-only audit trail (ADMIN only). {@code createdAt} is a UTC instant with an
 * explicit offset; {@code actorEmail} is the acting admin's e-mail, the only personal data in the row.
 */
public record OrderAuditEventResponse(
        Long id,
        OrderAuditEventType eventType,
        OrderProcessingStatus fromStatus,
        OrderProcessingStatus toStatus,
        boolean refundRequired,
        String actorEmail,
        OffsetDateTime createdAt
) {}
