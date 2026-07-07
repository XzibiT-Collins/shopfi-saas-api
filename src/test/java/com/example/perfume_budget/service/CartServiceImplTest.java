package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.cart.CartResponse;
import com.example.perfume_budget.dto.cart_item.request.CartItemRequest;
import com.example.perfume_budget.dto.cart_item.request.PopulateCartItemRequest;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.PartialSuccessException;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.CartItemService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.CartUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartRepository cartRepository;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private CartItemService cartItemService;
    @Mock
    private CartUtil cartUtil;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private EffectivePriceService effectivePriceService;

    @InjectMocks
    private CartServiceImpl cartService;

    private User testUser;
    private Cart testCart;
    private Product testProduct;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .fullName("Test User")
                .build();

        testProduct = Product.builder()
                .id(1L)
                .name("Perfume A")
                .price(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .build();

        testCart = Cart.builder()
                .id(1L)
                .user(testUser)
                .items(new ArrayList<>())
                .build();

        lenient().when(effectivePriceService.activeShopDiscount()).thenReturn(Optional.empty());
        lenient().when(effectivePriceService.now()).thenReturn(java.time.LocalDateTime.now());
        lenient().when(effectivePriceService.compute(any(Product.class), any(), any())).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            BigDecimal amount = p.getPrice().getAmount();
            return new EffectivePrice(amount, amount, p.getPrice().getCurrencyCode(),
                    false, DiscountSource.NONE, null, BigDecimal.ZERO, null);
        });
    }

    @Test
    void getCart_Success() {
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        CartResponse result = cartService.getCart();

        assertNotNull(result);
        verify(cartUtil).getCurrentUserCart();
    }

    @Test
    void clearCart_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);

        cartService.clearCart();

        verify(cartRepository).deleteAllByUser(testUser);
    }

    @Test
    void populateCartFromLocalStorage_Success() {
        CartItemRequest itemRequest = new CartItemRequest(1L, 2);
        PopulateCartItemRequest populateRequest = new PopulateCartItemRequest(List.of(itemRequest));

        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        cartService.populateCartFromLocalStorage(populateRequest);

        verify(cartItemService).addItemToCart(itemRequest);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void populateCartFromLocalStorage_PartialFailure() {
        CartItemRequest item1 = new CartItemRequest(1L, 2);
        CartItemRequest item2 = new CartItemRequest(2L, 1);
        PopulateCartItemRequest populateRequest = new PopulateCartItemRequest(List.of(item1, item2));

        when(cartItemService.addItemToCart(item1)).thenReturn(null);
        when(cartItemService.addItemToCart(item2)).thenThrow(new BadRequestException("Out of stock"));
        when(productRepository.findById(2L)).thenReturn(Optional.of(testProduct));

        assertThrows(PartialSuccessException.class, () -> cartService.populateCartFromLocalStorage(populateRequest));

        verify(cartItemService, times(2)).addItemToCart(any());
    }

    @Test
    void populateCartFromLocalStorage_Failure_ProductNotFound() {
        CartItemRequest item = new CartItemRequest(1L, 2);
        PopulateCartItemRequest populateRequest = new PopulateCartItemRequest(List.of(item));

        when(cartItemService.addItemToCart(item)).thenThrow(new BadRequestException("Out of stock"));
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(PartialSuccessException.class, () -> cartService.populateCartFromLocalStorage(populateRequest));
    }
}
