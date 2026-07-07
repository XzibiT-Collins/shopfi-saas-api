package com.example.perfume_budget.utils;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class CartUtil {
    private final AuthUserUtil authUserUtil;
    private final CartRepository cartRepository;

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
