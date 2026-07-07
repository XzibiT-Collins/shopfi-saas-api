package com.example.perfume_budget.config;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.model.LedgerAccount;
import com.example.perfume_budget.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChartOfAccountsSeeder implements ApplicationRunner {
    private final LedgerAccountRepository ledgerAccountRepository;

    @Override
    public void run(ApplicationArguments args) {
        seedAccount("1000", "Cash and Bank",           AccountType.ASSET,     AccountCategory.CASH);
        seedAccount("1001", "Mobile Money",            AccountType.ASSET,     AccountCategory.MOBILE_MONEY);
        seedAccount("1100", "Inventory Asset",         AccountType.ASSET,     AccountCategory.INVENTORY);

        seedAccount("2000", "Tax Payable",             AccountType.LIABILITY, AccountCategory.TAX_PAYABLE);
        seedAccount("2100", "Refunds Payable",         AccountType.LIABILITY, AccountCategory.REFUND_PAYABLE);
        seedAccount("2200", "Accounts Payable",        AccountType.LIABILITY, AccountCategory.ACCOUNTS_PAYABLE);
        seedAccount("2300", "Loans Payable",           AccountType.LIABILITY, AccountCategory.LOANS_PAYABLE);

        seedAccount("3000", "Owner's Equity",          AccountType.EQUITY,    AccountCategory.OWNERS_EQUITY);
        seedAccount("3100", "Owner's Capital",         AccountType.EQUITY,    AccountCategory.OWNERS_CAPITAL);
        seedAccount("3200", "Inventory Revaluation",   AccountType.EQUITY,    AccountCategory.INVENTORY_REVALUATION);

        seedAccount("4000", "Sales Revenue",           AccountType.REVENUE,   AccountCategory.SALES_REVENUE);
        seedAccount("4100", "Discount Expense",        AccountType.EXPENSE,   AccountCategory.DISCOUNT_EXPENSE);
        seedAccount("4200", "Coupon Expense",          AccountType.EXPENSE,   AccountCategory.COUPON_EXPENSE);
        seedAccount("4300", "Investment Income",       AccountType.REVENUE,   AccountCategory.INVESTMENT_INCOME);
        seedAccount("4400", "Inventory Increase",      AccountType.REVENUE,   AccountCategory.INVENTORY_INCREASE);

        seedAccount("5000", "Cost of Goods Sold",      AccountType.EXPENSE,   AccountCategory.COGS);
        seedAccount("5100", "Inventory Decrease",      AccountType.EXPENSE,   AccountCategory.INVENTORY_DECREASE);
        seedAccount("5200", "General Expenses",        AccountType.EXPENSE,   AccountCategory.GENERAL_EXPENSE);
        seedAccount("5300", "Marketing Expense",       AccountType.EXPENSE,   AccountCategory.MARKETING_EXPENSE);
        seedAccount("5400", "Logistics Expense",       AccountType.EXPENSE,   AccountCategory.LOGISTICS_EXPENSE);
        seedAccount("5500", "Miscellaneous Expense",   AccountType.EXPENSE,   AccountCategory.MISCELLANEOUS_EXPENSE);
        seedAccount("5600", "Inventory Variance",      AccountType.EXPENSE,   AccountCategory.INVENTORY_VARIANCE);
    }

    private void seedAccount(String code, String name, AccountType type, AccountCategory category) {
        if (!ledgerAccountRepository.existsByCode(code)) {
            ledgerAccountRepository.save(LedgerAccount.builder()
                    .code(code).name(name).type(type).category(category)
                    .build());
        }
    }
}
