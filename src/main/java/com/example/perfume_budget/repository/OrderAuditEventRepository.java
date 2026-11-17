package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.OrderAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderAuditEventRepository extends JpaRepository<OrderAuditEvent, Long> {
    List<OrderAuditEvent> findByOrderIdOrderByIdAsc(Long orderId);

    /** ORD-02 admin read endpoint: one order's rows, paged (index idx_order_audit_events_order (order_id, id)). */
    org.springframework.data.domain.Page<OrderAuditEvent> findByOrderId(Long orderId, org.springframework.data.domain.Pageable pageable);
}
