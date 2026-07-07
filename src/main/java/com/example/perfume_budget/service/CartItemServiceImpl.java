package com.example.perfume_budget.service;


import com.example.perfume_budget.dto.cart_item.request.CartItemRequest;
import com.example.perfume_budget.dto.cart_item.request.CartItemUpdateRequest;
import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;
import com.example.perfume_budget.events.CartTimesEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.CartItemMapper;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.CartItemRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.CartItemService;
import com.example.perfume_budget.utils.CartUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartItemServiceImpl implements CartItemService {
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CartUtil cartUtil;
    private final EffectivePriceService effectivePriceService;
    private static final String INVALID_QUANTITY = "Quantity must be greater than 0.";
    private static final String PRODUCT_NOT_FOUND = "Product not found.";
    private static final String STOCK_QUANTITY_EXCEEDED = "Selected quantity is above the stock quantity.";
    private static final String INACTIVE_PRODUCT = "Selected product is currently unavailable";
    private static final String UNENLISTED_PRODUCT = "Selected product is not available for ecommerce purchase.";
    private static final String CART_ITEM_NOT_FOUND = "Cart item not found or does not belong to you";
    private static final String PRODUCT_OUT_OF_STOCK = "Selected product is out of stock.";

    @Transactional
    @Override
    public CartItemResponse addItemToCart(CartItemRequest request) {
        if(request.quantity() <= 0) throw new BadRequestException(INVALID_QUANTITY);
        Product product = productRepository.findById(request.productId()).orElseThrow(() -> new ResourceNotFoundException(PRODUCT_NOT_FOUND));

        // check if a product is active
        if(!Boolean.TRUE.equals(product.getIsActive())) throw new BadRequestException(INACTIVE_PRODUCT);
        if(!Boolean.TRUE.equals(product.getIsEnlisted())) throw new BadRequestException(UNENLISTED_PRODUCT);

        // check if the product is out of stock
        if(product.getStockQuantity() == 0){
            throw new ResourceNotFoundException(PRODUCT_OUT_OF_STOCK);
        }
        // Get my cart
        Cart myCart = cartUtil.getCurrentUserCart();

        Optional<CartItem> existingCartItem = cartItemRepository.findByProductIdAndCartId(product.getId(), myCart.getId());

        // check if new total quantity > stock quantity
        int alreadyInCart = existingCartItem
                .map(CartItem::getQuantity)
                .orElse(0);

        if (request.quantity() + alreadyInCart > product.getStockQuantity()) {
            throw new BadRequestException(STOCK_QUANTITY_EXCEEDED);
        }

        CartItem cartItem;
        // Update the cart item by increasing item quantity or creating a new item if it doesnt exist

        cartItem = existingCartItem
                .map(existing -> {
                    existing.setQuantity(existing.getQuantity() + request.quantity());
                    return existing;
                })
                .orElseGet(() -> CartItemMapper.toCartItem(product, myCart, request));

        // save cart item
        cartItem = cartItemRepository.save(cartItem);

        // Publish event to handle add to cart count
        eventPublisher.publishEvent(CartTimesEvent.builder().productId(cartItem.getProduct().getId()).build());

        return CartItemMapper.toCartItemResponse(cartItem, effectivePriceService.compute(cartItem.getProduct()));
    }

    @Transactional
    @Override
    public CartItemResponse updateCartItem(Long cartItemId, CartItemUpdateRequest request) {
        if(request.quantity() <= 0){
            throw new BadRequestException(INVALID_QUANTITY);
        }

        CartItem cartItem = cartItemRepository.findByIdAndCartId(cartItemId,cartUtil.getCurrentUserCart().getId())
                .orElseThrow(() -> new ResourceNotFoundException(CART_ITEM_NOT_FOUND));

        if (!Boolean.TRUE.equals(cartItem.getProduct().getIsActive())) {
            throw new BadRequestException(INACTIVE_PRODUCT);
        }
        if (!Boolean.TRUE.equals(cartItem.getProduct().getIsEnlisted())) {
            throw new BadRequestException(UNENLISTED_PRODUCT);
        }
        if (request.quantity() > cartItem.getProduct().getStockQuantity()) {
            throw new BadRequestException(STOCK_QUANTITY_EXCEEDED);
        }

        cartItem.setQuantity(request.quantity());
        cartItem = cartItemRepository.save(cartItem);
        return CartItemMapper.toCartItemResponse(cartItem, effectivePriceService.compute(cartItem.getProduct()));
    }

    @Override
    public void removeCartItem(Long cartItemId) {
        int deleted = cartItemRepository.deleteByIdAndCart(cartItemId, cartUtil.getCurrentUserCart());
        if(deleted==0) throw new ResourceNotFoundException(CART_ITEM_NOT_FOUND);
    }
}
