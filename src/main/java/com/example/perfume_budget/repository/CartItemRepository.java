package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
}
