package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.PaymentProvider;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * A tenant's own payment gateway credentials (their Paystack account). Lives in the tenant
 * schema (no {@code schema=}, follows search_path), one row per tenant. The secret key is
 * stored AES-GCM encrypted, never in plaintext.
 */
@Getter
@Setter
@Builder
@Entity
@Table(name = "payment_gateway_config")
@AllArgsConstructor
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PaymentGatewayConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "secret_key_encrypted", columnDefinition = "TEXT")
    private String secretKeyEncrypted;

    @Column(name = "public_key")
    private String publicKey;

    @Column(nullable = false)
    private boolean enabled;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
