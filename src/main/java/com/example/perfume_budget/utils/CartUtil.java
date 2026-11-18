package com.example.perfume_budget.utils;

import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.service.DbLockTimeout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartUtil {
    /** CAT-04 rework: insert-if-absent + re-read rounds before a vanished cart becomes {@code 503 TRY_AGAIN}. */
    static final int CREATE_ATTEMPTS = 3;

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
     * CAT-04 rework (F-A): the current user's cart row-locked like {@link #getCurrentUserCartForUpdate()}, but NEVER
     * created. For update-quantity / remove-line: a customer with no cart has no line to change (404), so creating an
     * empty cart there would only be a side effect of a request that fails anyway. It also means a clear / settlement
     * that deleted the cart while the request was on its way simply answers "item not found". Must run inside a
     * transaction.
     */
    public Optional<Cart> findCurrentUserCartForUpdate() {
        return cartRepository.lockByUserId(authUserUtil.getCurrentUser().getId());
    }

    /**
     * CAT-03: find, else create race-free ({@code INSERT ... ON CONFLICT DO NOTHING}) and find again. Concurrent first
     * requests of one customer all end up with the same single cart row instead of a 409 on {@code uc_carts_user}.
     * The first find avoids the insert (and an identity value) on the common path where the cart already exists.
     *
     * <p>CAT-04 rework (F-A): the insert inserts nothing when another request's cart won the race, and a clear cart or a
     * payment settlement of this customer can delete that cart before the re-read (it locks the row FOR UPDATE, the
     * re-read waits for it and then finds no row). So insert-if-absent and re-read again, at most
     * {@value #CREATE_ATTEMPTS} times (each round either inserts this request's own cart, which nobody else can delete
     * before it commits, or found the row of a cart that was deleted in between); a cart that keeps vanishing is
     * {@code 503 TRY_AGAIN} (retry, nothing changed), never a 500.
     */
    private Cart findOrCreate(User user, Supplier<Optional<Cart>> find) {
        Optional<Cart> cart = find.get();
        for (int attempt = 1; cart.isEmpty() && attempt <= CREATE_ATTEMPTS; attempt++) {
            cartRepository.insertIfAbsent(user.getId(), LocalDateTime.now());
            cart = find.get();
        }
        return cart.orElseThrow(() -> {
            log.warn("Cart of user {} was deleted concurrently {} times while being created; answered 503 TRY_AGAIN",
                    user.getId(), CREATE_ATTEMPTS);
            return new ApiCodeException(HttpStatus.SERVICE_UNAVAILABLE, DbLockTimeout.TRY_AGAIN,
                    DbLockTimeout.TRY_AGAIN_MESSAGE);
        });
    }
}
