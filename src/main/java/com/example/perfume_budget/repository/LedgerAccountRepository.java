package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.model.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {
    boolean existsByCode(String code);

    Optional<LedgerAccount> findByCategory(AccountCategory accountCategory);

    List<LedgerAccount> findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType type);

    List<LedgerAccount> findAllByIsActiveTrueOrderByCodeAsc();
}
