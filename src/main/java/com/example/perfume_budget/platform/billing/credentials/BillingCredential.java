package com.example.perfume_budget.platform.billing.credentials;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * {@code master.billing_paystack_credentials}: one row per environment. {@code secretKeyEncrypted} is
 * {@link BillingSecretCipher} output and is never returned by any API; {@link #toString()} is redacted so a log line can
 * never carry it.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "billing_paystack_credentials", schema = "master")
public class BillingCredential {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private BillingEnvironment environment;

    @Column(name = "public_key", length = 160)
    private String publicKey;

    @Column(name = "public_last4", length = 4)
    private String publicLast4;

    @Column(name = "secret_key_encrypted", columnDefinition = "text")
    private String secretKeyEncrypted;

    @Column(name = "secret_key_version")
    private Short secretKeyVersion;

    @Column(name = "secret_last4", length = 4)
    private String secretLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CredentialStatus status;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "failure_code", length = 20)
    private String failureCode;

    @Column(name = "last_webhook_at")
    private Instant lastWebhookAt;

    @Column(name = "last_webhook_ok_at")
    private Instant lastWebhookOkAt;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by_admin_id")
    private Long updatedByAdminId;

    @Column(name = "updated_by_email")
    private String updatedByEmail;

    public boolean isConfigured() {
        return secretKeyEncrypted != null;
    }

    @Override
    public String toString() {
        return "BillingCredential[" + environment + ", status=" + status + "]";
    }
}
