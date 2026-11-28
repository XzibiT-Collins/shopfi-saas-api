package com.example.perfume_budget.platform.plan.repository;

import com.example.perfume_budget.platform.plan.model.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    /** Every plan in display order: {@code sortOrder}, then {@code id}. */
    @Query("select p from Plan p order by p.sortOrder asc, p.id asc")
    List<Plan> findAllOrdered();

    /** Public list (plans.md section 12): public AND active, display order. */
    @Query("select p from Plan p where p.isPublic = true and p.active = true order by p.sortOrder asc, p.id asc")
    List<Plan> findPublicActiveOrdered();

    Optional<Plan> findByCode(String code);

    /** The plan every new tenant gets (owner O1). Exactly one exists (plan rules, plans.md section 7). */
    @Query("select p from Plan p where p.isDefaultForSignup = true")
    Optional<Plan> findDefaultForSignup();

    /**
     * Row locks (native {@code SELECT ... FOR NO KEY UPDATE}) on EVERY plan, in id order. Every plan write takes them
     * first: the rules look at the whole catalog (one default, one public active plan) and "compare the version, then write"
     * must be atomic, so of two writers from the same version exactly one wins and the other sees the bumped version (409).
     * {@code FOR NO KEY UPDATE} (BILL-01c) instead of {@code FOR UPDATE}: it conflicts with every other writer of a plan row
     * but NOT with the {@code FOR KEY SHARE} that a foreign key check takes, so a sign-up inserting a tenant (or a tenant
     * plan change) never waits behind a console save. The caller sets {@code lock_timeout} first (bounded wait).
     */
    @Query(value = "select * from master.plans order by id asc for no key update", nativeQuery = true)
    List<Plan> lockAll();
}
