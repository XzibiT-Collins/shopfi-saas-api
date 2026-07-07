package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.DiscountType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Entity
@Table(name = "products",
        indexes = {
                @Index(name = "idx_product_name", columnList = "name"),
                @Index(name = "idx_product_price", columnList = "price_amount"),
                @Index(name = "idx_product_category", columnList = "category_id")
        })
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version = 0L; // for optimistic locking to prevent race conditions

    @Column(nullable = false)
    private String name;

    private String brand;

    private String size;

    @Column(unique = true, nullable = false)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String shortDescription;

    @Column(unique = true, nullable = false)
    private String sku;

    @Column(nullable = false)
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "price_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "price_currency"))
    private Money price;

    @AttributeOverride(name = "amount", column = @Column(name = "cost_price_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "cost_price_currency"))
    private Money costPrice;

    // ----- Product-specific discount (nullable; active only within [discountStartAt, discountEndAt]) -----
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", precision = 19, scale = 2)
    private BigDecimal discountValue; // percentage (0-100) when PERCENTAGE, flat amount when FLAT

    @Column(name = "discount_start_at")
    private LocalDateTime discountStartAt;

    @Column(name = "discount_end_at")
    private LocalDateTime discountEndAt;

    @Column(nullable = false)
    private Integer stockQuantity;

    private Integer soldCount = 0;

    private Integer lowStockThreshold = 5;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    private String imageUrl;

    private Boolean isActive = true;

    private Boolean isEnlisted = false;

    private Boolean isFeatured = false;

    private BigDecimal weight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_id")
    private ProductFamily family;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_of_measure_id")
    private UnitOfMeasure unitOfMeasure;

    private Integer conversionFactor = 1;

    private Boolean isBaseUnit = true;

    private Long viewCount = 0L;

    private Long addToCartCount = 0L;

    @CreatedDate
    @Column(name = "created_at", nullable =false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime  updatedAt;
}

