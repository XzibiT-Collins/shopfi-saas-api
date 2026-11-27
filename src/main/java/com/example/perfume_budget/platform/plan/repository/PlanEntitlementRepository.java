package com.example.perfume_budget.platform.plan.repository;

import com.example.perfume_budget.platform.plan.model.PlanEntitlement;
import com.example.perfume_budget.platform.plan.model.PlanEntitlementId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, PlanEntitlementId> {

    @Query("select e from PlanEntitlement e where e.id.planId in :planIds")
    List<PlanEntitlement> findByPlanIds(@Param("planIds") Collection<Long> planIds);
}
