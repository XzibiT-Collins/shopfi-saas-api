package com.example.perfume_budget.service;

import com.example.perfume_budget.repository.CartItemRepository;
import com.example.perfume_budget.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAT-04: cart row lock first, then every line of the cart (bulk), then the cart row. */
@ExtendWith(MockitoExtension.class)
class CartRemovalTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @InjectMocks CartRemoval cartRemoval;

    @Test
    void deleteCartOfUser_locksTheCartRowThenDeletesAllItsLinesThenTheRow() {
        when(cartRepository.lockIdForDeleteByUserId(7L)).thenReturn(Optional.of(70L));

        cartRemoval.deleteCartOfUser(7L);

        InOrder order = inOrder(cartRepository, cartItemRepository);
        order.verify(cartRepository).lockIdForDeleteByUserId(7L);
        order.verify(cartItemRepository).deleteAllByCartId(70L);
        order.verify(cartRepository).deleteRowById(70L);
    }

    @Test
    void deleteCartOfUserEmail_locksThenDeletesLinesThenRow() {
        when(cartRepository.lockIdsForDeleteByUserEmail("c@example.com")).thenReturn(List.of(70L));

        cartRemoval.deleteCartOfUserEmail("c@example.com");

        InOrder order = inOrder(cartRepository, cartItemRepository);
        order.verify(cartRepository).lockIdsForDeleteByUserEmail("c@example.com");
        order.verify(cartItemRepository).deleteAllByCartId(70L);
        order.verify(cartRepository).deleteRowById(70L);
    }

    @Test
    void noCart_deletesNothing() {
        when(cartRepository.lockIdForDeleteByUserId(7L)).thenReturn(Optional.empty());

        cartRemoval.deleteCartOfUser(7L);

        verify(cartItemRepository, never()).deleteAllByCartId(anyLong());
        verify(cartRepository, never()).deleteRowById(anyLong());
    }
}
