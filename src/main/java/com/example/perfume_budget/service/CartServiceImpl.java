package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.cart.CartResponse;
import com.example.perfume_budget.dto.cart_item.request.PopulateCartItemRequest;
import com.example.perfume_budget.events.CartTimesEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.PartialSuccessException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.CartMapper;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.CartItemService;
import com.example.perfume_budget.service.interfaces.CartService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.CartUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private final CartRepository cartRepository;
    private final AuthUserUtil authUserUtil;
    private final CartItemService cartItemService;
    private final CartUtil cartUtil;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EffectivePriceService effectivePriceService;

    @Transactional
    @Override
    public CartResponse getCart() {
        Cart myCart = cartUtil.getCurrentUserCart();
        return CartMapper.toCartResponse(myCart, effectivePriceService);
    }

    @Transactional
    @Override
    public void clearCart() {
        User currentUser = authUserUtil.getCurrentUser();
        // Delete old cart
        cartRepository.deleteAllByUser(currentUser);
        log.info("User: {} cleared their cart", currentUser.getFullName());
    }

    @Override
    public CartResponse populateCartFromLocalStorage(PopulateCartItemRequest requestList) {
        List<String> failedItems = new ArrayList<>();
        requestList.cartItems().forEach(item -> {
            try{
                cartItemService.addItemToCart(item);
            } catch (BadRequestException | ResourceNotFoundException e) {
                String productName = productRepository.findById(item.productId())
                        .map(Product::getName)
                        .orElse("Product ID " + item.productId());
                failedItems.add(productName + ": " + e.getMessage());
            }
            eventPublisher.publishEvent(CartTimesEvent.builder().productId(item.productId()).build());
        });

        if(!failedItems.isEmpty()){
            throw new PartialSuccessException("Some items could not be added to cart", failedItems);
        }
        return CartMapper.toCartResponse(cartUtil.getCurrentUserCart(), effectivePriceService);
    }
}
