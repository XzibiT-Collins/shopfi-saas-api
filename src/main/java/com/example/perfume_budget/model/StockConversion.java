package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.ConversionDirection;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_conversions")
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class StockConversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversion_number", unique = true, nullable = false)
    private String conversionNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_product_id", nullable = false)
    private Product fromProduct;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_product_id", nullable = false)
    private Product toProduct;

    @Column(name = "from_quantity", nullable = false)
    private Integer fromQuantity;

    @Column(name = "to_quantity", nullable = false)
    private Integer toQuantity;

    @Column(name = "from_cost_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal fromCostValue;

    @Column(name = "to_cost_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal toCostValue;

    @Column(name = "variance_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal varianceAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversionDirection direction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "converted_by_id", nullable = false)
    private User convertedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreatedDate
    @Column(name = "converted_at", nullable = false, updatable = false)
    private LocalDateTime convertedAt;
}
