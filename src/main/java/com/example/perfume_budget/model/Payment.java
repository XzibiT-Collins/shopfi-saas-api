package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.PaymentProvider;
import com.example.perfume_budget.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String systemReference;

    @Column(unique = true)
    private String providerPaymentReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentProvider provider;

    private String paymentMethod;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CurrencyCode currency = CurrencyCode.GHS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private String customerEmail;

    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String gatewayResponse;

    private LocalDateTime paidAt;

    /** Paystack authorization_url, kept so an idempotent replay returns the same payment page. */
    @Column(name = "authorization_url", length = 512)
    private String authorizationUrl;

    @Column(name = "access_code", length = 128)
    private String accessCode;

    @Column(name = "last_provider_check_at")
    private LocalDateTime lastProviderCheckAt;

    /** True when the customer paid for an order that could not be fulfilled any more (needs a manual refund). */
    @Column(name = "refund_required", nullable = false)
    @Builder.Default
    private boolean refundRequired = false;

    @CreatedDate
    @Column(name = "created_at", nullable =false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime  updatedAt;
}