package com.example.perfume_budget.utils;

import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.service.DbLockTimeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartUtilTest {

    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private CartRepository cartRepository;

    @InjectMocks
    private CartUtil cartUtil;

    @Test
    void getCurrentUserCart_ExistingCart() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();
        
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));

        Cart result = cartUtil.getCurrentUserCart();

        assertEquals(cart, result);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void getCurrentUserCart_NewCartIsInsertedIfAbsentThenReRead() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();

        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findByUser(user)).thenReturn(Optional.empty(), Optional.of(cart));

        Cart result = cartUtil.getCurrentUserCart();

        assertEquals(cart, result);
        verify(cartRepository).insertIfAbsent(eq(1L), any());
        verify(cartRepository, times(2)).findByUser(user);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void getCurrentUserCart_LostTheRace_StillReturnsTheWinnersCart() {
        // CAT-03: another request inserted the cart first: ON CONFLICT DO NOTHING inserts 0 rows, the re-read finds it
        User user = User.builder().id(1L).build();
        Cart winners = Cart.builder().id(11L).user(user).build();

        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findByUser(user)).thenReturn(Optional.empty(), Optional.of(winners));
        when(cartRepository.insertIfAbsent(eq(1L), any())).thenReturn(0);

        assertEquals(winners, cartUtil.getCurrentUserCart());
    }

    @Test
    void getCurrentUserCart_CartKeepsVanishing_Is503TryAgain_AfterThreeInsertRounds() {
        // CAT-04 rework F-A: every insert lost to a cart that a concurrent clear / settlement deleted before the re-read
        User user = User.builder().id(1L).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findByUser(user)).thenReturn(Optional.empty());

        ApiCodeException e = assertThrows(ApiCodeException.class, () -> cartUtil.getCurrentUserCart());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(DbLockTimeout.TRY_AGAIN, e.getCode());
        verify(cartRepository, times(3)).insertIfAbsent(eq(1L), any());
        verify(cartRepository, times(4)).findByUser(user);
    }

    @Test
    void getCurrentUserCartForUpdate_CartDeletedBetweenTheNoOpInsertAndTheReRead_InsertsAgain() {
        // CAT-04 rework F-A (QA suite b1): lock -> none; insert -> 0 rows (another request's cart won); a clear deleted
        // that cart; re-read -> none. Used to be IllegalStateException (500); now one more insert round finds a cart.
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.lockByUserId(1L)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(cart));
        when(cartRepository.insertIfAbsent(eq(1L), any())).thenReturn(0, 1);

        assertEquals(cart, cartUtil.getCurrentUserCartForUpdate());
        verify(cartRepository, times(2)).insertIfAbsent(eq(1L), any());
        verify(cartRepository, times(3)).lockByUserId(1L);
    }

    @Test
    void getCurrentUserCartForUpdate_ThirdRoundStillFindsTheCart() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.lockByUserId(1L)).thenReturn(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(cart));

        assertEquals(cart, cartUtil.getCurrentUserCartForUpdate());
        verify(cartRepository, times(3)).insertIfAbsent(eq(1L), any());
    }

    @Test
    void getCurrentUserCartForUpdate_CartKeepsVanishing_Is503TryAgain() {
        User user = User.builder().id(1L).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.lockByUserId(1L)).thenReturn(Optional.empty());

        ApiCodeException e = assertThrows(ApiCodeException.class, () -> cartUtil.getCurrentUserCartForUpdate());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(DbLockTimeout.TRY_AGAIN, e.getCode());
        verify(cartRepository, times(3)).insertIfAbsent(eq(1L), any());
    }

    @Test
    void findCurrentUserCartForUpdate_LockedRead_NeverCreates() {
        User user = User.builder().id(1L).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.lockByUserId(1L)).thenReturn(Optional.empty());

        assertTrue(cartUtil.findCurrentUserCartForUpdate().isEmpty());
        verify(cartRepository, times(1)).lockByUserId(1L);
        verify(cartRepository, never()).insertIfAbsent(any(), any());
    }

    @Test
    void getCurrentUserCartForUpdate_ExistingCart_LockedRead_NoInsert() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.lockByUserId(1L)).thenReturn(Optional.of(cart));

        assertEquals(cart, cartUtil.getCurrentUserCartForUpdate());
        verify(cartRepository, never()).insertIfAbsent(any(), any());
        verify(cartRepository, never()).findByUser(any());
    }

    @Test
    void getCurrentUserCartForUpdate_NoCart_InsertIfAbsentThenLockedReRead() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.lockByUserId(1L)).thenReturn(Optional.empty(), Optional.of(cart));

        assertEquals(cart, cartUtil.getCurrentUserCartForUpdate());
        verify(cartRepository).insertIfAbsent(eq(1L), any());
        verify(cartRepository, times(2)).lockByUserId(1L);
    }

    @Test
    void getCurrentUserCartWithItems_ExistingCartUsesTheFetchingQuery() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();

        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findWithItemsByUser(user)).thenReturn(Optional.of(cart));

        Cart result = cartUtil.getCurrentUserCartWithItems();

        assertEquals(cart, result);
        verify(cartRepository, never()).save(any());
        verify(cartRepository, never()).findByUser(any());
    }

    @Test
    void getCurrentUserCartWithItems_NewCartIsInsertedIfAbsentThenReReadWithItems() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();

        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findWithItemsByUser(user)).thenReturn(Optional.empty(), Optional.of(cart));

        Cart result = cartUtil.getCurrentUserCartWithItems();

        assertEquals(cart, result);
        verify(cartRepository).insertIfAbsent(eq(1L), any());
        verify(cartRepository, times(2)).findWithItemsByUser(user);
        verify(cartRepository, never()).save(any());
    }
}
