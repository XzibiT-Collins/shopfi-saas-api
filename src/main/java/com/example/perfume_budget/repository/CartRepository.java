package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUser(User user);

    void deleteByUser(User user);

    void deleteAllByUser(User user);
    @Modifying
    @Transactional
    void deleteAllByUserEmail(String email);
}
