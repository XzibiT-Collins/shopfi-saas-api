package com.example.perfume_budget.service;

import jakarta.persistence.EntityManager;

/**
 * THE place of transaction-scoped advisory locks (BILL-01c). Every lock uses the two-key form
 * {@code pg_advisory_xact_lock(int4 namespace, int4 hash)}: the namespace is one of the constants below, the hash is
 * {@code hashtext(<resource name>)}. Two subsystems can then never collide, however their hashes come out (equal hashes in
 * different namespaces are different locks; a hash collision inside one namespace only serialises). Released at commit or
 * rollback. Namespaces are never renumbered and never reused; add new ones at the end.
 */
public final class AdvisoryLocks {

    /** Count-and-insert per tenant and counted set ({@code UsageCounter}, plans.md 15.2). */
    public static final int PLAN_LIMIT = 1;
    /** Singleton theme row heal per tenant schema ({@code ThemeStore}). */
    public static final int THEME = 2;
    /** Default front-desk permission template saves per tenant schema. */
    public static final int FRONT_DESK_TEMPLATE = 3;
    /** Serialises every plan-catalog write (create / update / matrix) without blocking readers or foreign-key checks. */
    public static final int PLAN_CATALOG = 4;
    /** Reserved for billing (BILL-01b: per-tenant subscription state transitions). */
    public static final int BILLING = 5;
    /** Reserved for scheduled-job leader election. */
    public static final int LEADER_ELECTION = 6;

    /** SQL fragment with the two-key form; {@code :k} is the resource name. For native {@code @Query} use. */
    public static String sql(int namespace) {
        return "SELECT 1 FROM (SELECT pg_advisory_xact_lock(" + namespace + ", hashtext(:k))) l";
    }

    private AdvisoryLocks() {
    }

    /** Takes (waits for) the lock of {@code resource} in {@code namespace} until the transaction ends. */
    public static void lock(EntityManager em, int namespace, String resource) {
        em.createNativeQuery(sql(namespace)).setParameter("k", resource).getSingleResult();
    }
}
