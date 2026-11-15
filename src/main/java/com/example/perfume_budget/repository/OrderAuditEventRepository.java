package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.OrderAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderAuditEventRepository extends JpaRepository<OrderAuditEvent, Long> {
    List<OrderAuditEvent> findByOrderIdOrderByIdAsc(Long orderId);
}
