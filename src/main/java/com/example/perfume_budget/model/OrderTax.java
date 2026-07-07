package com.example.perfume_budget.model;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_taxes")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderTax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private String taxName;

    @Column(nullable = false)
    private String taxCode;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false, precision = 19, scale = 2)
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "taxable_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "taxable_currency"))
    private Money taxableAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "tax_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "tax_currency"))
    private Money taxAmount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
