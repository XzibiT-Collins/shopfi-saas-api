package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    boolean existsByProductIdAndCartId(Long productId, Long cartId);

    Optional<CartItem> findByProductIdAndCartId(Long id, Long id1);

    @Transactional
    @Modifying
    int deleteByIdAndCart(Long cartItemId, Cart cart);

    Optional<CartItem> findByIdAndCartId(Long cartItemId, Long id);

    /**
     * CAT-04: deletes EVERY line of the cart in one statement (not only the lines a caller had loaded: a line added
     * since would make the cart row delete fail on {@code fk_cart_items_on_cart}). Hold the cart row lock first.
     */
    @Modifying
    @Query(value = "DELETE FROM cart_items WHERE cart_id = :cartId", nativeQuery = true)
    int deleteAllByCartId(@Param("cartId") Long cartId);
}
