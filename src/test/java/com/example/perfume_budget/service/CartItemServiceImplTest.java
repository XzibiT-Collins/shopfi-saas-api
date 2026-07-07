package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.cart_item.request.CartItemRequest;
import com.example.perfume_budget.dto.cart_item.request.CartItemUpdateRequest;
import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.CartItemRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.utils.CartUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartItemServiceImplTest {

    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CartUtil cartUtil;
    @Mock
    private EffectivePriceService effectivePriceService;

    @InjectMocks
    private CartItemServiceImpl cartItemService;

    private User testUser;
    private Cart testCart;
    private Product testProduct;
    private CartItem testCartItem;

    @BeforeEach
    void setUp() {
        testUser = User.builder().id(1L).email("test@example.com").build();
        testCart = Cart.builder().id(1L).user(testUser).build();
        testProduct = Product.builder()
                .id(1L)
                .name("Perfume A")
                .price(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .stockQuantity(10)
                .isActive(true)
                .isEnlisted(true)
                .build();
        testCartItem = CartItem.builder()
                .id(1L)
                .product(testProduct)
                .cart(testCart)
                .quantity(2)
                .unitPrice(testProduct.getPrice())
                .build();

        lenient().when(effectivePriceService.compute(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            BigDecimal amount = p.getPrice().getAmount();
            return new EffectivePrice(amount, amount, p.getPrice().getCurrencyCode(),
                    false, DiscountSource.NONE, null, BigDecimal.ZERO, null);
        });
    }

    @Test
    void addItemToCart_Success_NewItem() {
        CartItemRequest request = new CartItemRequest(1L, 1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.findByProductIdAndCartId(1L, 1L)).thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(testCartItem);

        CartItemResponse result = cartItemService.addItemToCart(request);

        assertNotNull(result);
        verify(cartItemRepository).save(any(CartItem.class));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void addItemToCart_Success_ExistingItem() {
        CartItemRequest request = new CartItemRequest(1L, 1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.findByProductIdAndCartId(1L, 1L)).thenReturn(Optional.of(testCartItem));
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(testCartItem);

        CartItemResponse result = cartItemService.addItemToCart(request);

        assertNotNull(result);
        assertEquals(3, testCartItem.getQuantity());
    }

    @Test
    void addItemToCart_Failure_InvalidQuantity() {
        CartItemRequest request = new CartItemRequest(1L, 0);
        assertThrows(BadRequestException.class, () -> cartItemService.addItemToCart(request));
    }

    @Test
    void addItemToCart_Failure_ProductNotFound() {
        CartItemRequest request = new CartItemRequest(1L, 1);
        when(productRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> cartItemService.addItemToCart(request));
    }

    @Test
    void addItemToCart_Failure_ProductInactive() {
        testProduct.setIsActive(false);
        CartItemRequest request = new CartItemRequest(1L, 1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        assertThrows(BadRequestException.class, () -> cartItemService.addItemToCart(request));
    }

    @Test
    void addItemToCart_Failure_OutOfStock() {
        testProduct.setStockQuantity(0);
        CartItemRequest request = new CartItemRequest(1L, 1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        assertThrows(ResourceNotFoundException.class, () -> cartItemService.addItemToCart(request));
    }

    @Test
    void addItemToCart_Failure_StockExceeded() {
        CartItemRequest request = new CartItemRequest(1L, 11);
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.findByProductIdAndCartId(1L, 1L)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> cartItemService.addItemToCart(request));
    }

    @Test
    void addItemToCart_Failure_ProductNotEnlisted() {
        testProduct.setIsEnlisted(false);
        CartItemRequest request = new CartItemRequest(1L, 1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));

        assertThrows(BadRequestException.class, () -> cartItemService.addItemToCart(request));
    }

    @Test
    void updateCartItem_Success() {
        CartItemUpdateRequest request = new CartItemUpdateRequest(5);
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.findByIdAndCartId(1L, 1L)).thenReturn(Optional.of(testCartItem));
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(testCartItem);

        CartItemResponse result = cartItemService.updateCartItem(1L, request);

        assertNotNull(result);
        assertEquals(5, testCartItem.getQuantity());
    }

    @Test
    void updateCartItem_Failure_InvalidQuantity() {
        CartItemUpdateRequest request = new CartItemUpdateRequest(0);
        assertThrows(BadRequestException.class, () -> cartItemService.updateCartItem(1L, request));
    }

    @Test
    void updateCartItem_Failure_NotFound() {
        CartItemUpdateRequest request = new CartItemUpdateRequest(5);
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.findByIdAndCartId(1L, 1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> cartItemService.updateCartItem(1L, request));
    }

    @Test
    void updateCartItem_Failure_StockExceeded() {
        CartItemUpdateRequest request = new CartItemUpdateRequest(15);
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.findByIdAndCartId(1L, 1L)).thenReturn(Optional.of(testCartItem));

        assertThrows(BadRequestException.class, () -> cartItemService.updateCartItem(1L, request));
    }

    @Test
    void updateCartItem_Failure_ProductNotEnlisted() {
        testProduct.setIsEnlisted(false);
        CartItemUpdateRequest request = new CartItemUpdateRequest(5);
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.findByIdAndCartId(1L, 1L)).thenReturn(Optional.of(testCartItem));

        assertThrows(BadRequestException.class, () -> cartItemService.updateCartItem(1L, request));
    }

    @Test
    void removeCartItem_Success() {
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.deleteByIdAndCart(1L, testCart)).thenReturn(1);

        cartItemService.removeCartItem(1L);

        verify(cartItemRepository).deleteByIdAndCart(1L, testCart);
    }

    @Test
    void removeCartItem_Failure_NotFound() {
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(cartItemRepository.deleteByIdAndCart(1L, testCart)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> cartItemService.removeCartItem(1L));
    }
}
