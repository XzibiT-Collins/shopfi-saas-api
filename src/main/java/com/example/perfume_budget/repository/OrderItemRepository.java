package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.OrderItem;
import com.example.perfume_budget.projection.TopCompositionMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @Query("""
        SELECT oi.productId AS productId,
               p.name AS productName,
               p.imageUrl AS productImage,
               SUM(oi.quantity) AS totalSold,
               SUM(oi.quantity * oi.unitPrice.amount) AS totalRevenue
        FROM OrderItem oi
        JOIN Product p ON p.id = oi.productId
        JOIN oi.order o
        WHERE o.status = :status
        GROUP BY oi.productId, p.name, p.imageUrl
        ORDER BY totalRevenue DESC
        LIMIT 5
        """)
    List<TopCompositionMetric> findTopProductsByRevenue(@Param("status") PaymentStatus status);
}
