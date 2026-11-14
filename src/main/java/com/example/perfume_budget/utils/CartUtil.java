package com.example.perfume_budget.utils;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CartUtil {
    private final AuthUserUtil authUserUtil;
    private final CartRepository cartRepository;

    /** Read-only lookup: never creates a cart (safe inside a read-only transaction, e.g. the checkout preview). */
    public Optional<Cart> findCurrentUserCart() {
        return cartRepository.findByUser(authUserUtil.getCurrentUser());
    }

    public Cart getCurrentUserCart() {
        User currentUser = authUserUtil.getCurrentUser();
        return cartRepository.findByUser(currentUser)
                .orElseGet(() -> cartRepository.save(
                        Cart.builder()
                                .user(currentUser)
                                .items(new ArrayList<>())
                                .build()
                ));
    }
}
