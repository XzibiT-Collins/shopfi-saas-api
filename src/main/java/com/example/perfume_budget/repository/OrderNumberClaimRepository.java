package com.example.perfume_budget.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/** Atomic claim of a candidate order number ({@code INSERT ... ON CONFLICT DO NOTHING}), see V11 migration. */
@Repository
public class OrderNumberClaimRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** @return true when this caller now owns the number, false when it was already claimed */
    public boolean claim(String orderNumber) {
        int inserted = entityManager.createNativeQuery(
                        "INSERT INTO order_number_claims (order_number) VALUES (:n) ON CONFLICT (order_number) DO NOTHING")
                .setParameter("n", orderNumber)
                .executeUpdate();
        return inserted == 1;
    }
}
