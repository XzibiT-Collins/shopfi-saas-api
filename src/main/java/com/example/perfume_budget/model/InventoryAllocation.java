package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_allocations", indexes = {
        @Index(name = "idx_inventory_alloc_ref", columnList = "reference_type,reference_id,status"),
        @Index(name = "idx_inventory_alloc_product", columnList = "product_id,status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class InventoryAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "layer_id", nullable = false)
    private InventoryLayer layer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false)
    private InventoryReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private String referenceId;

    @Column(name = "reference_line_key", nullable = false)
    private String referenceLineKey;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "unit_selling_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitSellingPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false)
    private CurrencyCode currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryAllocationStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
