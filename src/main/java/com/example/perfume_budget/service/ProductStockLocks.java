package com.example.perfume_budget.service;

import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.StaleStateException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * API-05: the one place that serialises stock changes per product.
 *
 * <p>Root cause of the 500: inventory layers have no version and were read without a lock, so two checkouts of one
 * product both read the same {@code remaining_quantity}, both wrote it back decremented, and both bumped
 * {@code Product.version} through the stock refresh; the second commit then failed with an
 * ObjectOptimisticLockingFailureException (500). The version check was the only thing preventing an oversell or a
 * double legacy-layer bootstrap.
 *
 * <p>Fix: every writer of inventory layers / product stock first calls {@link #lock} with all the products it is going
 * to touch. That takes the product rows {@code FOR NO KEY UPDATE} in id order (one statement, so two multi-product
 * carts lock in the same order and cannot deadlock) and re-reads them; the caller then reads the layers. A writer that
 * holds the lock always sees the latest committed stock and version, so it neither loses an update nor fails the
 * version check. Lock order on every path stays: (customer row, ORD-01 | order row) -> products by id -> coupon row
 * last (API-04).
 */
@Component
@RequiredArgsConstructor
public class ProductStockLocks {

    private final ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Locks the products (id order) for the rest of the caller's transaction and refreshes the managed copies. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Collection<Long> productIds) {
        List<Long> ids = productIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            return;
        }
        // write pending changes first: the refresh below must not throw away anything this transaction changed
        flush();
        // EVT-01d: in a webhook settlement every wait of this one statement is limited to the budget left
        SettlementDeadline.beforeLockWait(entityManager);
        productRepository.lockForStockUpdate(ids);
        for (Long id : ids) {
            Product product = entityManager.find(Product.class, id);
            if (product != null) {
                entityManager.refresh(product); // a copy loaded before the lock may carry an old stock / version
            }
        }
    }

    /**
     * API-05 rework: this is a plain {@code @Component}, so Spring's exception translation does not wrap what the
     * shared EntityManager throws. A version conflict while flushing the caller's pending changes would surface as JPA's
     * own {@link OptimisticLockException} (409) instead of the {@code 503 TRY_AGAIN} every other optimistic conflict
     * gives. Translate it: the transaction is rolled back, nothing changed, the client retries.
     */
    private void flush() {
        try {
            entityManager.flush();
        } catch (OptimisticLockException | StaleStateException e) {
            throw new ObjectOptimisticLockingFailureException("Concurrent update while taking the product stock locks", e);
        }
    }

    /** Re-reads an entity (e.g. an inventory layer) that may have been loaded before the product lock was taken. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T fresh(T entity) {
        entityManager.refresh(entity);
        return entity;
    }
}
