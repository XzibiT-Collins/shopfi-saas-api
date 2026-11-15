package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.DiscountType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private DiscountType discountType; // PERCENTAGE or FLAT

    @Column(nullable = false)
    private BigDecimal discountValue; // Percentage value or Flat value

    private BigDecimal maximumDiscountAmount;  // cap for percentage discounts
    private BigDecimal minimumCartAmountForDiscount;

    @Column(nullable = false)
    private Integer usageLimit; // cap on the number of applications

    private Integer usageCount = 0;

    private Integer perUserUsageLimit;

    /**
     * True only while the coupon is inactive because a redemption reached {@code usageLimit} (API-04 rework). A
     * released redemption re-activates such a coupon; an admin write of {@code isActive} clears it.
     */
    @Column(name = "limit_deactivated", nullable = false)
    private boolean limitDeactivated;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate expirationDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}