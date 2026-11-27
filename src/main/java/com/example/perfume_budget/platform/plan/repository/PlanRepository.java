package com.example.perfume_budget.platform.plan.repository;

import com.example.perfume_budget.platform.plan.model.Plan;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
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
     * Row locks (SELECT ... FOR UPDATE) on EVERY plan, in id order. Every plan write takes them first: the rules look at
     * the whole catalog (one default, one public active plan) and "compare the version, then write" must be atomic, so of
     * two writers from the same version exactly one wins and the other sees the bumped version (409). The catalog is a
     * handful of rows; the wait is bounded (lock timeout), never indefinite.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select p from Plan p order by p.id asc")
    List<Plan> lockAll();
}
