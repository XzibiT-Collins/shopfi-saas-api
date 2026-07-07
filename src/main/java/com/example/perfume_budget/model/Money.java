package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

@Getter
@Embeddable
public class Money {

    @Column(precision = 19, scale = 2) // DECIMAL(19,4)
    private BigDecimal amount;

    @Column(length = 3) // ISO currency code
    @Enumerated(EnumType.STRING)
    private CurrencyCode currencyCode;

    protected Money() {} // JPA requires default constructor

    public Money(BigDecimal amount, CurrencyCode currency) {
        this.amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        this.currencyCode = currency;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currencyCode.name());
    }

    @Override
    public String toString() {
        return currencyCode + " " + amount.toPlainString();
    }
}