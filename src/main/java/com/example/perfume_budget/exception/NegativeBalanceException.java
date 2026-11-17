package com.example.perfume_budget.exception;

import com.example.perfume_budget.enums.AccountCategory;

import java.math.BigDecimal;

/**
 * ORD-02 rework (F2): a posting would take a guarded ledger account (Cash and Bank, Mobile Money, Inventory, or any
 * account on a manual entry) below zero. Still a 400 {@link BadRequestException} with the historic message; callers
 * that own a coded error (refund acknowledgement: 409 INSUFFICIENT_CASH) catch it and translate.
 */
public class NegativeBalanceException extends BadRequestException {
    private final AccountCategory category;
    private final BigDecimal balanceBefore;
    private final BigDecimal attemptedDeduction;

    public NegativeBalanceException(String accountName, AccountCategory category, BigDecimal balanceBefore,
                                    BigDecimal attemptedDeduction, BigDecimal balanceAfter) {
        super(accountName + " cannot go negative. Current balance: " + balanceAfter
                + ", attempted deduction: " + attemptedDeduction);
        this.category = category;
        this.balanceBefore = balanceBefore;
        this.attemptedDeduction = attemptedDeduction;
    }

    public AccountCategory getCategory() {
        return category;
    }

    /** The committed balance before this posting (the posting is rolled back with the transaction). */
    public BigDecimal getBalanceBefore() {
        return balanceBefore;
    }

    public BigDecimal getAttemptedDeduction() {
        return attemptedDeduction;
    }
}
