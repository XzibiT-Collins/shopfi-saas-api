package com.example.perfume_budget.service;

import com.example.perfume_budget.repository.CartItemRepository;
import com.example.perfume_budget.repository.CartRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAT-04: deletes a customer's cart (clear cart, payment settlement) in the cart lock order every cart writer follows:
 * <ol>
 *   <li>the cart row, {@code SELECT ... FOR UPDATE}: waits for an in-flight add-item / update / remove of this
 *       customer (they hold the cart row {@code FOR NO KEY UPDATE}) and blocks new ones until this transaction ends;</li>
 *   <li>every line of that cart, one bulk {@code DELETE FROM cart_items WHERE cart_id = ?}: a new statement, so it
 *       also sees the lines the add-item we waited for committed;</li>
 *   <li>the cart row.</li>
 * </ol>
 * Before (CAT-03 review F1/F2) the deleters removed the lines they had loaded first and the cart row last, the reverse
 * of add-item (cart row, then line): an add-item of a product already in the cart deadlocked with them, and a line
 * added concurrently made the cart row delete fail on {@code fk_cart_items_on_cart} (clear 409, webhook 500).
 *
 * <p>Global lock order (see docs/api/checkout.md, "Cart lock order"): user → order → payment → cart → cart_items →
 * products → ledger accounts → coupon. Joins the caller's transaction; never starts one.
 */
@Component
@RequiredArgsConstructor
public class CartRemoval {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Deletes the user's cart and all its lines, if there is one (clear cart; payment settlement: the order's user). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteCartOfUser(Long userId) {
        SettlementDeadline.beforeLockWait(entityManager); // EVT-01d: a settlement's wait is limited to its budget left
        cartRepository.lockIdForDeleteByUserId(userId).ifPresent(this::deleteLocked);
    }

    private void deleteLocked(Long cartId) {
        cartItemRepository.deleteAllByCartId(cartId);
        cartRepository.deleteRowById(cartId);
    }
}
