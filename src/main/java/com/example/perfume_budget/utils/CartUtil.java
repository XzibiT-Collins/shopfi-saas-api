package com.example.perfume_budget.utils;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CartUtil {
    private static final String CART_NOT_CREATED = "The cart could not be created.";

    private final AuthUserUtil authUserUtil;
    private final CartRepository cartRepository;

    /** Read-only lookup: never creates a cart (safe inside a read-only transaction, e.g. the checkout preview). */
    public Optional<Cart> findCurrentUserCart() {
        return cartRepository.findByUser(authUserUtil.getCurrentUser());
    }

    /**
     * CAT-02: the current user's cart with items and products already loaded (one query), for mapping to a response
     * outside a transaction. Creates the (empty) cart if the user has none yet, like {@link #getCurrentUserCart()}.
     */
    public Cart getCurrentUserCartWithItems() {
        User currentUser = authUserUtil.getCurrentUser();
        return findOrCreate(currentUser, () -> cartRepository.findWithItemsByUser(currentUser));
    }

    public Cart getCurrentUserCart() {
        User currentUser = authUserUtil.getCurrentUser();
        return findOrCreate(currentUser, () -> cartRepository.findByUser(currentUser));
    }

    /**
     * CAT-03: the current user's cart (created if missing), row-locked until the caller's transaction ends. For cart
     * writes that read-then-modify lines (add-item): concurrent writes of one customer queue on the cart row.
     * Must run inside a transaction.
     */
    public Cart getCurrentUserCartForUpdate() {
        User currentUser = authUserUtil.getCurrentUser();
        return findOrCreate(currentUser, () -> cartRepository.lockByUserId(currentUser.getId()));
    }

    /**
     * CAT-03: find, else create race-free ({@code INSERT ... ON CONFLICT DO NOTHING}) and find again. Concurrent first
     * requests of one customer all end up with the same single cart row instead of a 409 on {@code uc_carts_user}.
     * The first find avoids the insert (and an identity value) on the common path where the cart already exists.
     */
    private Cart findOrCreate(User user, Supplier<Optional<Cart>> find) {
        return find.get().orElseGet(() -> {
            cartRepository.insertIfAbsent(user.getId(), LocalDateTime.now());
            return find.get().orElseThrow(() -> new IllegalStateException(CART_NOT_CREATED));
        });
    }
}
