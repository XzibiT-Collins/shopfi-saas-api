package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    void deleteByUser(User user);

    void deleteAllByUser(User user);
    @Modifying
    @Transactional
    void deleteAllByUserEmail(String email);
}
