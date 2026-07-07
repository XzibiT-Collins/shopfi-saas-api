package com.example.perfume_budget.dto.accounts.response;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;

import java.math.BigDecimal;

public record AccountBalanceResponse(
        String accountCode,
        String accountName,
        AccountType accountType,
        AccountCategory accountCategory,
        BigDecimal balance
) {}
