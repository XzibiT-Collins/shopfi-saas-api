package com.example.perfume_budget.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Order order;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    private String sku;

    @Column(nullable = false)
    private Integer quantity;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "unit_price_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "unit_price_currency"))
    private Money unitPrice;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "cost_price_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "cost_price_currency"))
    private Money costPrice;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_price_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "total_price_currency"))
    private Money totalPrice;

    @CreatedDate
    @Column(name = "created_at", nullable =false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime  updatedAt;
}