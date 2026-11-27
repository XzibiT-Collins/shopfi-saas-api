package com.example.perfume_budget.platform.plan.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One feature of one plan (master.plan_entitlements). The key is stored as text so a key that a later release removes
 * from {@code PlanFeature} never breaks a read: it is ignored (and logged) instead. {@code limitValue} null = unlimited
 * for a LIMIT feature; always null for a BOOLEAN one.
 */
@Getter
@Setter
@Entity
@Table(name = "plan_entitlements", schema = "master")
@AllArgsConstructor
@NoArgsConstructor
public class PlanEntitlement {

    @EmbeddedId
    private PlanEntitlementId id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "limit_value")
    private Integer limitValue;
}
