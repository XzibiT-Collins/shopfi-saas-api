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
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Order> {
    boolean existsByOrderNumber(String orderNumber);

    Page<Order> findAllByUser(Pageable pageable, User user);

    List<Order> findAllByUser(User user);

    Optional<Order> findByOrderNumber(String cleanOrderNumber);

    Optional<Order> findByOrderNumberAndUser(String cleanOrderNumber, User user);

    /**
     * Row lock on one order (SELECT ... FOR UPDATE). Every state transition that can release or consume the
     * order's stock reservation (webhook success/failure, provider verify, owner cancel, expiry job) takes this lock
     * first and re-reads the status, so each reservation is released or consumed exactly once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> lockById(@Param("id") Long id);

    @Query("SELECT o.id FROM Order o WHERE o.orderNumber = :orderNumber AND o.user = :user")
    Optional<Long> findIdByOrderNumberAndUser(@Param("orderNumber") String orderNumber, @Param("user") User user);

    @Query("SELECT o.id FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Long> findIdByOrderNumber(@Param("orderNumber") String orderNumber);

    Optional<Order> findByUserAndIdempotencyKey(User user, String idempotencyKey);

    /**
     * PAY-03: the customer's open orders (payment INITIATED and created after {@code cutoff}, i.e. still inside the
     * reservation TTL), newest first, whatever request they were created for. Checkout allows at most one.
     */
    @Query("""
        SELECT o FROM Order o
        WHERE o.user = :user
          AND o.status = com.example.perfume_budget.enums.PaymentStatus.INITIATED
          AND o.createdAt > :cutoff
        ORDER BY o.createdAt DESC, o.id DESC
        """)
    List<Order> findOpenByUser(@Param("user") User user, @Param("cutoff") LocalDateTime cutoff);

    // API-RD-10: the customer metrics list moved to CustomerMetricsQueryRepository (search, sort, tie-breaker).

    /**
     * Orders by this customer that still hold a LIVE redemption of the coupon: not released (API-04 rework) and not
     * FAILED / CANCELLED (the status test also covers rows changed outside the application).
     */
    @Query("""
        SELECT COUNT(o.id) FROM Order o
        WHERE o.user.id = :userId AND o.coupon.id = :couponId
        AND o.couponRedemptionReleased = false
        AND o.status NOT IN (com.example.perfume_budget.enums.PaymentStatus.FAILED,
                             com.example.perfume_budget.enums.PaymentStatus.CANCELLED)
        """)
    long countCouponRedemptionsByUser(@Param("userId") Long userId, @Param("couponId") Long couponId);

    /** Orders of any customer that still hold a live redemption of the coupon (delete check, COUPON_REDEEMED). */
    @Query("""
        SELECT COUNT(o.id) FROM Order o
        WHERE o.coupon.id = :couponId
        AND o.couponRedemptionReleased = false
        AND o.status NOT IN (com.example.perfume_budget.enums.PaymentStatus.FAILED,
                             com.example.perfume_budget.enums.PaymentStatus.CANCELLED)
        """)
    long countLiveCouponRedemptions(@Param("couponId") Long couponId);

    /**
     * Coupon delete: row-locks every order that references the coupon, in id order, BEFORE the coupon row is locked
     * (the same order -> coupon lock order as cancel / payment webhooks), so a late payment cannot revive one of them
     * while the coupon is being deleted.
     */
    @Query(value = "SELECT id FROM orders WHERE coupon_id = :couponId ORDER BY id FOR UPDATE", nativeQuery = true)
    List<Long> lockIdsByCouponId(@Param("couponId") Long couponId);

    /** Coupon delete: released (cancelled / failed) orders lose the link; their discount amounts are kept. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query(value = "UPDATE orders SET coupon_id = NULL WHERE coupon_id = :couponId "
            + "AND (coupon_redemption_released OR status IN ('CANCELLED', 'FAILED'))",
            nativeQuery = true)
    int detachReleasedOrdersFromCoupon(@Param("couponId") Long couponId);

    @Query("""
        SELECT COUNT(o.id) AS totalUsages,
               COALESCE(SUM(o.discountAmount.amount), 0) AS totalDiscountGiven,
               COALESCE(SUM(o.totalAmount.amount), 0) AS revenueGenerated
        FROM Order o
        WHERE o.coupon.id = :couponId
        AND o.status = :status
        AND (o.deliveryStatus IS NULL OR o.deliveryStatus NOT IN (com.example.perfume_budget.enums.OrderProcessingStatus.CANCELLED, com.example.perfume_budget.enums.OrderProcessingStatus.RETURNED))
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
        AND (o.deliveryStatus IS NULL OR o.deliveryStatus NOT IN (com.example.perfume_budget.enums.OrderProcessingStatus.CANCELLED, com.example.perfume_budget.enums.OrderProcessingStatus.RETURNED))
        GROUP BY o.coupon.id, o.coupon.code, o.coupon.expirationDate
        ORDER BY revenueGenerated DESC
        """)
    List<CouponMetrics> getAllCouponMetrics(@Param("status") PaymentStatus status);

    @Query("""
        SELECT CAST(o.createdAt AS date) AS date,
               COALESCE(SUM(o.totalAmount.amount), 0) AS revenue
        FROM Order o
        WHERE o.status = :status
        AND (o.deliveryStatus IS NULL OR o.deliveryStatus NOT IN (com.example.perfume_budget.enums.OrderProcessingStatus.CANCELLED, com.example.perfume_budget.enums.OrderProcessingStatus.RETURNED))
        AND o.createdAt BETWEEN :start AND :end
        GROUP BY CAST(o.createdAt AS date)
        ORDER BY CAST(o.createdAt AS date) ASC
        """)
    List<DailyRevenueMetric> getDailyRevenue(
            @Param("status") PaymentStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);


    @Query("SELECT COALESCE(SUM(o.totalAmount.amount), 0) FROM Order o WHERE o.status = :status"
            + " AND (o.deliveryStatus IS NULL OR o.deliveryStatus NOT IN (com.example.perfume_budget.enums.OrderProcessingStatus.CANCELLED, com.example.perfume_budget.enums.OrderProcessingStatus.RETURNED))")
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
        AND (o.deliveryStatus IS NULL OR o.deliveryStatus NOT IN (com.example.perfume_budget.enums.OrderProcessingStatus.CANCELLED, com.example.perfume_budget.enums.OrderProcessingStatus.RETURNED))
        GROUP BY u.id, u.fullName, u.email
        ORDER BY totalOrders DESC, totalAmountSpent DESC
        """)
    List<TopCustomer> findTopCustomers(@Param("status") PaymentStatus status, Pageable pageable);

    @Query("""
        SELECT COUNT(o.id)
        FROM Order o
        WHERE o.status = com.example.perfume_budget.enums.PaymentStatus.COMPLETED
          AND (o.deliveryStatus IS NULL OR o.deliveryStatus NOT IN (com.example.perfume_budget.enums.OrderProcessingStatus.CANCELLED, com.example.perfume_budget.enums.OrderProcessingStatus.RETURNED))
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
