package com.example.perfume_budget.platform.billing.settings;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Append-only by construction: insert and reads only (the table's triggers refuse UPDATE / DELETE / TRUNCATE as well). */
public interface BillingSettingsAuditRepository extends Repository<BillingSettingsAuditLog, Long> {

    BillingSettingsAuditLog saveAndFlush(BillingSettingsAuditLog row);

    @Query("select a from BillingSettingsAuditLog a order by a.id desc")
    Page<BillingSettingsAuditLog> newestFirst(Pageable pageable);

    long count();
}
