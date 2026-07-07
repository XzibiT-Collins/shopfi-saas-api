package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.WalkInOrder;
import com.example.perfume_budget.enums.WalkInOrderStatus;
import com.example.perfume_budget.projection.WalkInOrderStatusProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WalkInOrderRepository extends JpaRepository<WalkInOrder, Long> {
    Optional<WalkInOrder> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    Page<WalkInOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<WalkInOrder> findAllByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(LocalDateTime start,
                                                                              Pageable pageable);

    @Query("""
            SELECT COUNT(w.id)
            FROM WalkInOrder w
            WHERE w.status = com.example.perfume_budget.enums.WalkInOrderStatus.COMPLETED
              AND w.createdAt BETWEEN :start AND :end
            """)
    long countCompletedWalkInOrders(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("""
            SELECT w.status AS status, COUNT(w.id) AS count
            FROM WalkInOrder w
            WHERE w.createdAt BETWEEN :start AND :end
            GROUP BY w.status
            """)
    List<WalkInOrderStatusProjection> countWalkInOrdersByStatus(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
