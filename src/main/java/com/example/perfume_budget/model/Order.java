package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @Column(nullable = false)
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "subtotal_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "subtotal_currency"))
    private Money subtotal;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "discount_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "discount_amount_currency"))
    private Money discountAmount;

    // Product-specific + shop-wide discount baked into the effective subtotal.
    // subtotal is NET of this; gross subtotal = subtotal + automaticDiscountAmount.
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "automatic_discount_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "automatic_discount_currency"))
    private Money automaticDiscountAmount;

    @Column(nullable = false)
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "total_amount_currency"))
    private Money totalAmount;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    private OrderProcessingStatus deliveryStatus;

    private String paymentReference;

    @ManyToOne
    private Coupon coupon;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderTax> taxes = new ArrayList<>();

    @Column(nullable = false, precision = 19, scale = 2)
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_tax_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "tax_currency"))
    private Money totalTaxAmount = new Money(BigDecimal.ZERO, CurrencyCode.GHS);

    @CreatedDate
    @Column(name = "created_at", nullable =false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime  updatedAt;
}