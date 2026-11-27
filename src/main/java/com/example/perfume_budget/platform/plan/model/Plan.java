package com.example.perfume_budget.platform.plan.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A subscription plan (master.plans, PLAN-01). Prices are integer pesewas; the annual price is derived
 * ({@code PlanPricing}), never stored. {@link #version} is the optimistic-lock value of the API: it is compared and
 * bumped by {@code PlanAdminService} under a row lock (not JPA {@code @Version}), because an entitlement-only change
 * must bump it too and a stale write must answer the dedicated {@code 409 PLAN_VERSION_CONFLICT}.
 */
@Getter
@Setter
@Builder
@Entity
@Table(name = "plans", schema = "master")
@AllArgsConstructor
@NoArgsConstructor
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32, updatable = false)
    private String code;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(length = 40)
    private String tagline;

    @Column(length = 280)
    private String description;

    @Column(name = "monthly_price_pesewas", nullable = false)
    private long monthlyPricePesewas;

    @Column(name = "annual_discount_percent", nullable = false)
    private short annualDiscountPercent;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "GHS";

    @Column(name = "trial_days", nullable = false)
    private int trialDays;

    @Column(name = "transaction_fee_bps", nullable = false)
    private int transactionFeeBps;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @Column(name = "is_default_for_signup", nullable = false)
    private boolean isDefaultForSignup;

    @Column(nullable = false)
    private boolean highlighted;

    @Column(name = "badge_label", length = 40)
    private String badgeLabel;

    @Column(name = "bullets_heading", length = 80)
    private String bulletsHeading;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "marketing_bullets", nullable = false, columnDefinition = "jsonb")
    private List<String> marketingBullets = new ArrayList<>();

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
