package com.example.perfume_budget.accounting;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.isolation.AbstractTenantIsolationTest;
import com.example.perfume_budget.model.LedgerAccount;
import com.example.perfume_budget.repository.LedgerAccountRepository;
import com.example.perfume_budget.support.TestTenant;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ORD-02b: the ledger balance is only ever moved by the atomic {@code applyBalanceDelta} UPDATE. A LedgerAccount entity
 * loaded before that UPDATE in the same transaction still holds the old balance; a later dirty flush of that entity
 * (any other field changed) must not write the stale balance back over the delta.
 */
class LedgerAccountStaleBalanceIntegrationTest extends AbstractTenantIsolationTest {

    @Autowired LedgerAccountRepository ledgerAccounts;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager txManager;

    private BigDecimal storedBalance(TestTenant t, String code) {
        return jdbc.queryForObject("SELECT balance FROM " + t.schemaName() + ".ledger_accounts WHERE code = ?", BigDecimal.class, code);
    }

    @Test
    void aDirtySaveAfterTheAtomicDeltaKeepsTheDeltaAppliedBalance() {
        TestTenant acme = tenants.acme();
        String code = "1000"; // Cash and Bank
        BigDecimal before = storedBalance(acme, code);
        String name = jdbc.queryForObject("SELECT name FROM tenant_acme.ledger_accounts WHERE code = ?", String.class, code);
        try {
            tenants.runAs(acme, () -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
                LedgerAccount cash = ledgerAccounts.findByCategory(AccountCategory.CASH).orElseThrow();
                BigDecimal returned = ledgerAccounts.applyBalanceDelta(cash.getId(), new BigDecimal("12.34"));
                assertThat(returned).isEqualByComparingTo(before.add(new BigDecimal("12.34")));
                assertThat(cash.getBalance()).as("the managed entity is stale after the native UPDATE").isEqualByComparingTo(before);
                cash.setName(name + " (renamed)"); // another field: the entity is dirty
                ledgerAccounts.save(cash);
                entityManager.flush();
            }));
            assertThat(storedBalance(acme, code)).as("the delta survives the dirty flush")
                    .isEqualByComparingTo(before.add(new BigDecimal("12.34")));
            assertThat(jdbc.queryForObject("SELECT name FROM tenant_acme.ledger_accounts WHERE code = ?", String.class, code))
                    .as("the other field was written").isEqualTo(name + " (renamed)");
        } finally {
            jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ?, name = ? WHERE code = ?", before, name, code);
        }
    }

    @Test
    void aNewAccountIsStillCreatedWithItsInitialBalance() {
        TestTenant acme = tenants.acme();
        String code = "9" + (System.nanoTime() % 100000);
        try {
            tenants.runAs(acme, () -> new TransactionTemplate(txManager).executeWithoutResult(s ->
                    ledgerAccounts.save(LedgerAccount.builder().code(code).name("ORD-02b opening balance")
                            .type(AccountType.ASSET).category(AccountCategory.MISCELLANEOUS_EXPENSE).balance(new BigDecimal("55.00")).build())));
            assertThat(storedBalance(acme, code)).isEqualByComparingTo("55.00");
        } finally {
            jdbc.update("DELETE FROM tenant_acme.ledger_accounts WHERE code = ?", code);
        }
    }
}
