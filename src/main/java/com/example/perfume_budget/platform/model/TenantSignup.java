package com.example.perfume_budget.platform.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Pending-onboarding state for a tenant (master.tenant_signups). Secrets are stored only as hashes: a BCrypt
 * hash of the owner's password and a salted SHA-256 of the OTP. toString is deliberately not generated.
 */
@Getter
@Setter
@Builder
@Entity
@Table(name = "tenant_signups", schema = "master")
@AllArgsConstructor
@NoArgsConstructor
public class TenantSignup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private Long tenantId;

    @Column(name = "owner_full_name", nullable = false)
    private String ownerFullName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "otp_salt", nullable = false, length = 64)
    private String otpSalt;

    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Column(name = "otp_expires_at", nullable = false)
    private LocalDateTime otpExpiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "resend_count", nullable = false)
    private int resendCount;

    @Column(name = "last_otp_sent_at")
    private LocalDateTime lastOtpSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
