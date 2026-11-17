package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.ShopWideDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShopWideDiscountRepository extends JpaRepository<ShopWideDiscount, Long> {

    /**
     * The shop-wide discount currently in effect: active and "now" within its window.
     * Newest first so the most recently created one wins if several overlap.
     */
    @Query("""
            SELECT s FROM ShopWideDiscount s
            WHERE s.isActive = true
              AND s.startAt <= :now
              AND s.endAt >= :now
            ORDER BY s.createdAt DESC
            """)
    List<ShopWideDiscount> findActive(@Param("now") LocalDateTime now);

    default Optional<ShopWideDiscount> findActiveNow(LocalDateTime now) {
        return findActive(now).stream().findFirst();
    }

    List<ShopWideDiscount> findByIsActiveTrueOrderByCreatedAtDesc();

    /**
     * API-06: every discount that is switched on and has not ended at {@code now}: the one in force and any scheduled
     * for later. Soonest start first (newest first on a tie). Bounded by {@code page}.
     */
    @Query("""
            SELECT s FROM ShopWideDiscount s
            WHERE s.isActive = true
              AND s.endAt >= :now
            ORDER BY s.startAt ASC, s.createdAt DESC, s.id DESC
            """)
    List<ShopWideDiscount> findNotEnded(@Param("now") LocalDateTime now, org.springframework.data.domain.Pageable page);
}
