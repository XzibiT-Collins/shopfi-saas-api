package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "ledger_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code; // e.g. "1000", "2000", "4000"

    @Column(nullable = false)
    private String name; // e.g. "Cash", "Tax Payable", "Sales Revenue"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type; // ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountCategory category; // CASH, REVENUE, DISCOUNT, TAX, REFUND etc.

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    /**
     * ORD-02b: written once, at INSERT (a new account's opening balance), and afterwards ONLY by the atomic
     * {@code LedgerAccountRepository#applyBalanceDelta} UPDATE. Not updatable through the entity: an entity loaded before
     * that UPDATE in the same transaction holds the old value, and a later dirty flush (any other field changed) would
     * otherwise write the stale balance back over the delta. Re-read the account (or use the value the UPDATE returned)
     * for the current balance.
     */
    @Builder.Default
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal balance = BigDecimal.ZERO;
}
