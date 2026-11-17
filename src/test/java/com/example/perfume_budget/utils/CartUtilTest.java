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
    void getCurrentUserCart_NewCart() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(10L).user(user).build();

        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findByUser(user)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenReturn(cart);

        Cart result = cartUtil.getCurrentUserCart();

        assertEquals(cart, result);
        verify(cartRepository).save(any(Cart.class));
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
    void getCurrentUserCartWithItems_NewCartIsCreatedEmptyForThatUser() {
        User user = User.builder().id(1L).build();

        when(authUserUtil.getCurrentUser()).thenReturn(user);
        when(cartRepository.findWithItemsByUser(user)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        Cart result = cartUtil.getCurrentUserCartWithItems();

        assertEquals(user, result.getUser());
        assertNotNull(result.getItems());
        assertTrue(result.getItems().isEmpty());
        verify(cartRepository).save(any(Cart.class));
    }
}
