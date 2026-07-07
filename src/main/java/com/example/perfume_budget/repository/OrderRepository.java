package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.projection.CouponMetrics;
import com.example.perfume_budget.projection.CustomerOrderSummary;
import com.example.perfume_budget.projection.DailyRevenueMetric;
import com.example.perfume_budget.projection.TopCustomer;
import com.example.perfume_budget.projection.OnlineOrderStatusProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    boolean existsByOrderNumber(String orderNumber);

    Page<Order> findAllByUser(Pageable pageable, User user);

    List<Order> findAllByUser(User user);

    Optional<Order> findByOrderNumber(String cleanOrderNumber);

    Optional<Order> findByOrderNumberAndUser(String cleanOrderNumber, User user);

    @Query("""
        SELECT u.id AS userId, u.fullName AS fullName, u.email AS email,
               u.isActive AS isActive, u.createdAt AS createdAt,
               COUNT(o.id) AS totalOrders,
               COALESCE(SUM(o.totalAmount.amount), 0) AS totalAmountSpent
        FROM Order o
        JOIN o.user u
        GROUP BY u.id, u.fullName, u.email, u.isActive, u.createdAt
        ORDER BY COUNT(o.id) DESC
        """)
    Page<CustomerOrderSummary> getCustomerOrderSummary(Pageable pageable);

    @Query("""
        SELECT u.id AS userId, u.fullName AS fullName, u.email AS email,
               u.isActive AS isActive, u.createdAt AS createdAt,
               COUNT(o.id) AS totalOrders,
               COALESCE(SUM(o.totalAmount.amount), 0) AS totalAmountSpent
        FROM Order o
        JOIN o.user u
        WHERE u.id = :userId
        GROUP BY u.id, u.fullName, u.email, u.isActive, u.createdAt
        """)
    Optional<CustomerOrderSummary> getCustomerOrderSummaryByUserId(@Param("userId") Long userId);

    @Query("""
        SELECT COUNT(o.id) AS totalUsages,
               COALESCE(SUM(o.discountAmount.amount), 0) AS totalDiscountGiven,
               COALESCE(SUM(o.totalAmount.amount), 0) AS revenueGenerated
        FROM Order o
        WHERE o.coupon.id = :couponId
        AND o.status = :status
        """)
    CouponMetrics getCouponMetrics(@Param("couponId") Long couponId,
                                   @Param("status") PaymentStatus status);

    @Query("""
        SELECT o.coupon.code AS couponCode,
               o.coupon.expirationDate as expirationDate,
               COUNT(o.id) AS totalUsages,
               COALESCE(SUM(o.discountAmount.amount), 0) AS totalDiscountGiven,
               COALESCE(SUM(o.totalAmount.amount), 0) AS revenueGenerated
        FROM Order o
        WHERE o.coupon IS NOT NULL
        AND o.status = :status
        GROUP BY o.coupon.id, o.coupon.code, o.coupon.expirationDate
        ORDER BY revenueGenerated DESC
        """)
    List<CouponMetrics> getAllCouponMetrics(@Param("status") PaymentStatus status);

    @Query("""
        SELECT CAST(o.createdAt AS date) AS date,
               COALESCE(SUM(o.totalAmount.amount), 0) AS revenue
        FROM Order o
        WHERE o.status = :status
        AND o.createdAt BETWEEN :start AND :end
        GROUP BY CAST(o.createdAt AS date)
        ORDER BY CAST(o.createdAt AS date) ASC
        """)
    List<DailyRevenueMetric> getDailyRevenue(
            @Param("status") PaymentStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);


    @Query("SELECT COALESCE(SUM(o.totalAmount.amount), 0) FROM Order o WHERE o.status = :status")
    BigDecimal getTotalRevenue(@Param("status") PaymentStatus status);

    @Query("""
        SELECT COUNT(o.id)
        FROM Order o
        WHERE o.deliveryStatus = :status
        """)
    long countOrdersByStatus(@Param("status") OrderProcessingStatus status);

    @Query("""
        SELECT u.fullName AS fullName, u.email AS email,
               COUNT(o.id) AS totalOrders,
               COALESCE(SUM(o.totalAmount.amount), 0) AS totalAmountSpent
        FROM Order o
        JOIN o.user u
        WHERE o.status = :status
        GROUP BY u.id, u.fullName, u.email
        ORDER BY totalOrders DESC, totalAmountSpent DESC
        """)
    List<TopCustomer> findTopCustomers(@Param("status") PaymentStatus status, Pageable pageable);

    @Query("""
        SELECT o FROM Order o
        WHERE (:paymentStatus IS NULL OR o.status = :paymentStatus)
        AND (:deliveryStatus IS NULL OR o.deliveryStatus = :deliveryStatus)
        ORDER BY o.createdAt DESC
        """)
    Page<Order> findAllWithFilters(@Param("paymentStatus") PaymentStatus paymentStatus,
                                   @Param("deliveryStatus") OrderProcessingStatus deliveryStatus,
                                   Pageable pageable);

    @Query("""
        SELECT COUNT(o.id)
        FROM Order o
        WHERE o.status = com.example.perfume_budget.enums.PaymentStatus.COMPLETED
          AND o.createdAt BETWEEN :start AND :end
        """)
    long countCompletedOnlineOrders(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("""
        SELECT o.deliveryStatus AS deliveryStatus, COUNT(o.id) AS count
        FROM Order o
        WHERE o.createdAt BETWEEN :start AND :end
        GROUP BY o.deliveryStatus
        """)
    List<OnlineOrderStatusProjection> countOnlineOrdersByDeliveryStatus(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
