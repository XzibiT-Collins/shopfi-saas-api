package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.StockTransferType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_transfers", indexes = {
        @Index(name = "idx_stock_transfers_product_created", columnList = "product_id,created_at"),
        @Index(name = "idx_stock_transfers_ref", columnList = "reference_type,reference_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class StockTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // Null = stock entering from outside (receipt / adjustment-in).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_location_id")
    private StorageLocation fromLocation;

    // Null = stock leaving the building (sale / adjustment-out).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_location_id")
    private StorageLocation toLocation;

    @Column(nullable = false)
    private Integer quantity;

    // Null for system-driven paths (e.g. payment webhooks).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moved_by")
    private User movedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, length = 32)
    private StockTransferType transferType;

    // On-hand balance at the affected location immediately after this move (null for rows
    // recorded before this column existed). Gives historical per-location stock at sale time.
    @Column(name = "balance_after")
    private Integer balanceAfter;

    // Ties the move to its originating order/adjustment (null for plain transfers / legacy rows).
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 32)
    private InventoryReferenceType referenceType;

    @Column(name = "reference_id")
    private String referenceId;

    @Column(columnDefinition = "TEXT")
    private String note;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
