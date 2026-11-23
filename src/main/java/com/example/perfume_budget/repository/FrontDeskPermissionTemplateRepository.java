package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.FrontDeskPermissionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FrontDeskPermissionTemplateRepository extends JpaRepository<FrontDeskPermissionTemplate, Long> {
    List<FrontDeskPermissionTemplate> findAllByEnabledTrue();

    /**
     * API-RD-11c: transaction-scoped advisory lock that serialises default-template saves of ONE tenant (the template
     * has no single row to lock; {@code current_schema()} keeps tenants apart). Released at commit / rollback.
     */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(current_schema() || ':front_desk_template'))) l",
            nativeQuery = true)
    Integer lockTemplateSaves();
}
