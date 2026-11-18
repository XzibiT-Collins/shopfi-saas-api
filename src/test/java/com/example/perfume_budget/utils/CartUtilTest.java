package com.example.perfume_budget.utils;

import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    void getCurrentUserCart_NoRowEvenAfterTheInsert_IsAnIllegalState() {
        User user = User.builder().id(1L).build();
        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findByUser(user)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> cartUtil.getCurrentUserCart());
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
