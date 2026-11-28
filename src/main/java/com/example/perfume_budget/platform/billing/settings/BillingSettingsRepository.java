package com.example.perfume_budget.platform.billing.settings;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface BillingSettingsRepository extends JpaRepository<BillingSettings, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select s from BillingSettings s where s.id = 1")
    BillingSettings lockRow();

    @Query("select s from BillingSettings s where s.id = 1")
    BillingSettings row();
}
