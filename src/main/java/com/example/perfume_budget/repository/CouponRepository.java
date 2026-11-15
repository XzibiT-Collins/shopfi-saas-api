package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCode(String upperCase);

    boolean existsByCode(String code);

    /**
     * Row-locks the coupon for an admin edit / deactivate / delete: {@code SELECT ... FOR NO KEY UPDATE}, NOT the
     * {@code FOR UPDATE} Hibernate emits for PESSIMISTIC_WRITE. A checkout whose new order references the coupon
     * holds Postgres' FK {@code KEY SHARE} lock on this row until it commits (after the Paystack call); FOR UPDATE
     * would wait for that, FOR NO KEY UPDATE does not. The callers set a lock_timeout first (CouponLocks).
     */
    @Query(value = "SELECT * FROM coupons WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Coupon> lockById(@Param("id") Long id);

    @Query("SELECT c.id FROM Coupon c WHERE c.code = :code")
    Optional<Long> findIdByCode(@Param("code") String code);

    /**
     * Atomically reserves one redemption (API-04 rework): increments usage_count only while the coupon is active,
     * inside its date window and under its limit. Postgres re-checks the WHERE clause after waiting for a row lock,
     * so concurrent reservations can never exceed usage_limit and an admin deactivate that commits first is seen.
     * The coupon is switched off (limit_deactivated = true) by the reservation that reaches the limit.
     * Runs in its own short transaction: the row lock is released before any HTTP call.
     * @return 1 when reserved, 0 when the coupon cannot be redeemed (or no longer exists)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE coupons
            SET usage_count = COALESCE(usage_count, 0) + 1,
                is_active = (COALESCE(usage_count, 0) + 1 < usage_limit),
                limit_deactivated = (COALESCE(usage_count, 0) + 1 >= usage_limit)
            WHERE id = :id
              AND is_active
              AND start_date <= :today
              AND expiration_date >= :today
              AND COALESCE(usage_count, 0) < usage_limit
            """, nativeQuery = true)
    int reserveRedemption(@Param("id") Long id, @Param("today") java.time.LocalDate today);

    /**
     * Gives one redemption back (never below zero). Re-activates the coupon only when it is inactive solely because a
     * redemption reached the limit (limit_deactivated) and the count is now under the limit again.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE coupons
            SET usage_count = GREATEST(COALESCE(usage_count, 0) - 1, 0),
                is_active = CASE WHEN limit_deactivated AND GREATEST(COALESCE(usage_count, 0) - 1, 0) < usage_limit
                                 THEN TRUE ELSE is_active END,
                limit_deactivated = CASE WHEN limit_deactivated AND GREATEST(COALESCE(usage_count, 0) - 1, 0) < usage_limit
                                         THEN FALSE ELSE limit_deactivated END
            WHERE id = :id
            """, nativeQuery = true)
    int releaseRedemption(@Param("id") Long id);

    /**
     * Takes a redemption again for an order a late payment revived. Unconditional (the customer paid with the
     * discount); switches an active coupon off at the limit exactly like a reservation does.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE coupons
            SET usage_count = COALESCE(usage_count, 0) + 1,
                limit_deactivated = CASE WHEN is_active AND COALESCE(usage_count, 0) + 1 >= usage_limit
                                         THEN TRUE ELSE limit_deactivated END,
                is_active = CASE WHEN is_active AND COALESCE(usage_count, 0) + 1 >= usage_limit
                                 THEN FALSE ELSE is_active END
            WHERE id = :id
            """, nativeQuery = true)
    int reacquireRedemption(@Param("id") Long id);

//    Page<Coupon> findAll(Pageable pageable);
}
