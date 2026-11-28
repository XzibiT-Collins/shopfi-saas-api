package com.example.perfume_budget.platform.billing.credentials;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BillingCredentialRepository extends JpaRepository<BillingCredential, BillingEnvironment> {

    /** Row lock with a bounded wait: a stale writer then fails the version comparison (409), not a lock error. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select c from BillingCredential c where c.environment = :environment")
    BillingCredential lock(@Param("environment") BillingEnvironment environment);

    /** TEST first, then LIVE (the contract's order; the stored text would sort LIVE first). */
    default List<BillingCredential> all() {
        List<BillingCredential> rows = new java.util.ArrayList<>(findAll());
        rows.sort(java.util.Comparator.comparing(BillingCredential::getEnvironment));
        return rows;
    }
}
