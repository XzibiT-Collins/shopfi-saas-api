package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.WalkInOrderAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalkInOrderAuditEventRepository extends JpaRepository<WalkInOrderAuditEvent, Long> {
    /** The request this key already made on this order (tenant = current schema). */
    Optional<WalkInOrderAuditEvent> findByWalkInOrderIdAndIdempotencyKey(Long walkInOrderId, String idempotencyKey);

    List<WalkInOrderAuditEvent> findByWalkInOrderIdOrderByIdAsc(Long walkInOrderId);
}
