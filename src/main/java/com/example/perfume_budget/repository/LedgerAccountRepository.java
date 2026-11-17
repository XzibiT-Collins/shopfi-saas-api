package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.model.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {
    boolean existsByCode(String code);

    Optional<LedgerAccount> findByCategory(AccountCategory accountCategory);

    List<LedgerAccount> findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType type);

    List<LedgerAccount> findAllByIsActiveTrueOrderByCodeAsc();

    /**
     * ORD-02 rework (F1): adds {@code delta} to the stored balance in ONE atomic statement and returns the new balance
     * (null when the account does not exist). The UPDATE row-locks the account until the transaction ends, so
     * concurrent postings queue on the row instead of overwriting each other (no read-modify-write in memory). The
     * returned value is the balance including every committed posting, which is what the negative-balance guard checks.
     */
    @Transactional
    @Query(value = "UPDATE ledger_accounts SET balance = balance + :delta WHERE id = :id RETURNING balance", nativeQuery = true)
    BigDecimal applyBalanceDelta(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
