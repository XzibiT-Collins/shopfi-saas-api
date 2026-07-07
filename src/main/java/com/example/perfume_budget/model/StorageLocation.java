package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.StorageLocationType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "storage_locations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_storage_locations_name", columnNames = "name")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class StorageLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StorageLocationType type;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @Builder.Default
    @Column(name = "is_default_receiving", nullable = false)
    private boolean isDefaultReceiving = false;

    @Builder.Default
    @Column(name = "is_walk_in_sale_source", nullable = false)
    private boolean isWalkInSaleSource = false;

    @Builder.Default
    @Column(name = "is_ecommerce_fulfilment_source", nullable = false)
    private boolean isEcommerceFulfilmentSource = false;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
