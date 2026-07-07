package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.WalkInOrderStatus;
import com.example.perfume_budget.enums.WalkInPaymentMethod;
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
@Table(name = "walk_in_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class WalkInOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_user_id")
    private User registeredUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "walk_in_customer_id")
    private WalkInCustomer walkInCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by_id", nullable = false)
    private User processedBy;

    @OneToMany(mappedBy = "walkInOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WalkInOrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "walkInOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WalkInOrderTax> taxes = new ArrayList<>();

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "subtotal_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "subtotal_currency"))
    private Money subtotal;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "discount_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "discount_currency"))
    private Money discountAmount;

    // Product-specific + shop-wide discount baked into the effective subtotal.
    // subtotal is NET of this; gross subtotal = subtotal + automaticDiscountAmount.
    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "automatic_discount_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "automatic_discount_currency"))
    private Money automaticDiscountAmount;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_tax_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "total_tax_currency"))
    @Builder.Default
    private Money totalTaxAmount = new Money(BigDecimal.ZERO, CurrencyCode.GHS);

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_amount"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "total_currency"))
    private Money totalAmount;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "amount_paid"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "amount_paid_currency"))
    private Money amountPaid;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "change_given"))
    @AttributeOverride(name = "currencyCode", column = @Column(name = "change_given_currency"))
    private Money changeGiven;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type")
    private DiscountType discountType;

    @Column(name = "discount_value", precision = 19, scale = 2)
    private BigDecimal discountValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalkInPaymentMethod paymentMethod;

    @Column(precision = 19, scale = 2)
    private BigDecimal splitCashAmount;

    @Column(precision = 19, scale = 2)
    private BigDecimal splitMobileAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalkInOrderStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Boolean receiptPrinted = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
