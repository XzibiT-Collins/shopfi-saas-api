package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUser(User user);

    /**
     * CAT-02: the cart with its items and each item's product in ONE query (entity graph = left joins), so the cart can
     * be mapped outside a transaction (open-in-view is off) and without a lazy load per line (no N+1).
     */
    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Cart> findWithItemsByUser(User user);

    /**
     * CAT-03: creates the user's (empty) cart unless one exists, race-free. Concurrent first cart writes of one customer
     * used to all see "no cart", all insert, and every loser failed on {@code uc_carts_user} (409). With ON CONFLICT
     * DO NOTHING a loser waits for the winner's insert to commit (or roll back) and inserts nothing; the caller then
     * re-reads the row (READ COMMITTED: a fresh snapshot per statement sees the winner's cart). Returns rows inserted.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO carts (user_id, created_at, updated_at) VALUES (:userId, :now, :now) "
            + "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * CAT-03: the user's cart, row-locked until the calling transaction ends, so one customer's cart writes (add-item,
     * also from populate) run one after the other: no lost quantity update and no duplicate (cart, product) line.
     * {@code FOR NO KEY UPDATE}: does not wait for the FK key-share locks other rows hold on the cart.
     */
    @Query(value = "SELECT * FROM carts WHERE user_id = :userId FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Cart> lockByUserId(@Param("userId") Long userId);

    /**
     * CAT-04: the id of the user's cart, row-locked {@code FOR UPDATE} (the row is about to be deleted) until the
     * calling transaction ends. Taken BEFORE any line is touched, like every other cart writer (cart row, then lines):
     * an add-item holding the cart lock is waited for here, never in the middle of the line deletes. See
     * {@code CartRemoval}.
     */
    @Query(value = "SELECT id FROM carts WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockIdForDeleteByUserId(@Param("userId") Long userId);

    /** CAT-04: as {@link #lockIdForDeleteByUserId}, for the cart of the user with this e-mail (payment settlement). */
    @Query(value = "SELECT c.id FROM carts c JOIN users u ON u.id = c.user_id WHERE u.email = :email "
            + "ORDER BY c.id FOR UPDATE OF c", nativeQuery = true)
    List<Long> lockIdsForDeleteByUserEmail(@Param("email") String email);

    /** CAT-04: deletes the cart row; its lines must be gone already ({@code fk_cart_items_on_cart}). Lock it first. */
    @Modifying
    @Query(value = "DELETE FROM carts WHERE id = :cartId", nativeQuery = true)
    int deleteRowById(@Param("cartId") Long cartId);
}
