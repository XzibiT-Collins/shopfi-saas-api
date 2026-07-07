package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.order.OrderListResponse;
import com.example.perfume_budget.dto.order.OrderResponse;
import com.example.perfume_budget.dto.order.OrderStatusUpdateRequest;
import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.PaymentException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.OrderItemMapper;
import com.example.perfume_budget.mapper.OrderMapper;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.*;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.OrderService;
import com.example.perfume_budget.service.interfaces.PaymentGatewayAPIService;
import com.example.perfume_budget.service.interfaces.TaxService;
import com.example.perfume_budget.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final AuthUserUtil authUserUtil;
    private final CartUtil cartUtil;
    private final CouponRepository couponRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final PaymentReferenceGenerator paymentReferenceGenerator;
    private final PaymentGatewayAPIService gatewayAPIService;
    private final DeliveryDetailRepository deliveryDetailRepository;
    private final TaxService taxService;
    private final InventoryManagementService inventoryManagementService;
    private final DiscountCalculationUtil discountCalculationUtil;
    private final EffectivePriceService effectivePriceService;
    private static final String INVALID_COUPON_CODE = "Invalid coupon code.";
    private static final String COUPON_WITH_DISCOUNT = "Coupons cannot be combined with active product discounts.";
    private static final String ORDER_NOT_FOUND = "Order not found.";
    private static final String EMPTY_CART = "Cart is empty, add items and try again.";
    private static final String PRODUCT_UNAVAILABLE = "One or more products in your cart are no longer available for ecommerce purchase.";


    @Transactional
    @Override
    public PaystackInitiateTransactionResponse checkout(String couponCode) {
        User currentUser = authUserUtil.getCurrentUser();
        deliveryDetailRepository.findByUserAndIsDefaultTrue(currentUser).orElseThrow(() -> new BadRequestException("Please add a delivery address to continue."));
        Cart cart = cartUtil.getCurrentUserCart();

        if (cart.getItems().isEmpty()) {
            throw new BadRequestException(EMPTY_CART);
        }

        validateCartItemsForEcommerce(cart);

        // Resolve effective (discounted) prices once for the whole cart.
        ShopWideDiscount activeShop = effectivePriceService.activeShopDiscount().orElse(null);
        LocalDateTime pricingTime = effectivePriceService.now();

        BigDecimal orderSubtotal = BigDecimal.ZERO;
        BigDecimal automaticDiscount = BigDecimal.ZERO;
        boolean anyItemOnSale = false;
        for (CartItem cartItem : cart.getItems()) {
            EffectivePrice effectivePrice = effectivePriceService.compute(cartItem.getProduct(), activeShop, pricingTime);
            if (effectivePrice.onSale()) {
                anyItemOnSale = true;
            }
            BigDecimal quantity = BigDecimal.valueOf(cartItem.getQuantity());
            orderSubtotal = orderSubtotal.add(effectivePrice.effectiveAmount().multiply(quantity));
            automaticDiscount = automaticDiscount.add(
                    effectivePrice.originalAmount().subtract(effectivePrice.effectiveAmount()).multiply(quantity));
        }

        Coupon coupon = null;
        BigDecimal finalPrice = orderSubtotal;
        if (couponCode != null) {
            if (anyItemOnSale) {
                throw new BadRequestException(COUPON_WITH_DISCOUNT);
            }
            coupon = couponRepository.findByCode(couponCode.strip().toUpperCase())
                    .orElseThrow(() -> new BadRequestException(INVALID_COUPON_CODE));
            discountCalculationUtil.checkIfCouponIsValid(coupon);
            finalPrice = discountCalculationUtil.applyDiscount(coupon, orderSubtotal);
        }

        TaxCalculationResult taxCalculationResult = taxService.calculateTaxes(finalPrice);

        BigDecimal discountAmount = orderSubtotal.subtract(finalPrice);
        Order newOrder = Order.builder()
                .orderNumber(orderNumberGenerator.generateOrderNumber())
                .user(currentUser)
                .subtotal(new Money(orderSubtotal, CurrencyCode.GHS))
                .discountAmount(new Money(discountAmount, CurrencyCode.GHS))
                .automaticDiscountAmount(new Money(automaticDiscount, CurrencyCode.GHS))
                .totalTaxAmount(taxCalculationResult.totalTaxAmount())
                .totalAmount(taxCalculationResult.totalAmountAfterTax())
                .status(PaymentStatus.PENDING)
                .deliveryStatus(OrderProcessingStatus.PENDING)
                .coupon(coupon)
                .build();

        newOrder.setItems(createOrderItems(cart, newOrder, activeShop, pricingTime));
        Order finalNewOrder = newOrder;
        taxCalculationResult.orderTaxes().forEach(tax-> tax.setOrder(finalNewOrder));
        newOrder.setTaxes(new ArrayList<>(taxCalculationResult.orderTaxes()));
        newOrder = orderRepository.save(newOrder);

        // Save payment as PENDING before any API call
        Payment newPayment = createPaymentObject(newOrder);
        newPayment.setCustomerEmail(currentUser.getEmail());
        paymentRepository.save(newPayment);

        try {
            reserveStock(newOrder.getOrderNumber(), newOrder.getItems());

            PaystackInitiatePaymentRequest request = PaystackInitiatePaymentRequest.builder()
                    .email(currentUser.getEmail())
                    .amount(convertAmountToSend(newOrder.getTotalAmount().getAmount()))
                    .currency(newOrder.getTotalAmount().getCurrencyCode())
                    .build();

            PaystackInitiateTransactionResponse response = gatewayAPIService.initiatePaystackTransaction(request);
            log.info("Payment initiated for order: {}", newOrder.getOrderNumber());
            log.info("Response: {}", response);

            newPayment.setProviderPaymentReference(response.data().reference());
            newPayment.setStatus(PaymentStatus.INITIATED);
            paymentRepository.save(newPayment);

            newOrder.setStatus(PaymentStatus.INITIATED);
            eventPublisher.publishEvent(orderRepository.save(newOrder));
            return response;

        } catch (PaymentException exception) {
            releaseStock(newOrder.getOrderNumber());

            newOrder.setStatus(PaymentStatus.FAILED);
            orderRepository.save(newOrder);

            newPayment.setStatus(PaymentStatus.FAILED);
            newPayment.setFailureReason(exception.getMessage());
            paymentRepository.save(newPayment);

            log.error("Payment initiation failed for order {}: {}", newOrder.getOrderNumber(), exception.getMessage());
            throw exception;
        }
    }

    private BigDecimal convertAmountToSend(BigDecimal amount) {
        return amount.multiply(new BigDecimal("100"));
    }

    private Payment createPaymentObject(Order newOrder) {
        return Payment.builder()
                .systemReference(paymentReferenceGenerator.generateReference())
                .provider(PaymentProvider.PAYSTACK)
                .order(newOrder)
                .amount(newOrder.getTotalAmount().getAmount())
                .currency(newOrder.getTotalAmount().getCurrencyCode())
                .status(PaymentStatus.PENDING)
                .build();
    }

    @Override
    public PageResponse<OrderListResponse> getMyOrders(Pageable pageable) {
        User user = authUserUtil.getCurrentUser();
        Page<OrderListResponse> myOrders = orderRepository.findAllByUser(pageable, user)
                .map(OrderMapper::toOrderListResponse);

        return PaginationUtil.createPageResponse(myOrders);
    }

    @Override
    public OrderResponse getOrderByOrderNumber(String orderNumber) {
        return OrderMapper.toOrderResponse(getOrder(orderNumber));
    }

    @Override
    public PageResponse<OrderListResponse> getAllOrders(PaymentStatus paymentStatus,
                                                        OrderProcessingStatus deliveryStatus,
                                                        Pageable pageable) {
        Page<OrderListResponse> allOrders = orderRepository
                .findAllWithFilters(paymentStatus, deliveryStatus, pageable)
                .map(OrderMapper::toOrderListResponse);
        return PaginationUtil.createPageResponse(allOrders);
    }

    @Override
    public OrderResponse updateOrderStatus(String orderNumber, OrderStatusUpdateRequest request) {
        Order order = getOrder(orderNumber);
        if(order.getDeliveryStatus() == OrderProcessingStatus.DELIVERED){
            throw new BadRequestException("Order is already delivered and can not be updated.");
        }
        if (order.getDeliveryStatus() == OrderProcessingStatus.CANCELLED) {
            throw new BadRequestException("Order is already cancelled and can not be updated.");
        }
        if(order.getDeliveryStatus() != request.orderStatus()){
            order.setDeliveryStatus(request.orderStatus());
            eventPublisher.publishEvent(OrderStatusChangeEvent.builder()
                            .orderNumber(orderNumber)
                            .customerEmail(order.getUser().getEmail())
                            .status(request.orderStatus())
                    .build());
            return OrderMapper.toOrderResponse(orderRepository.save(order));
        }

        return OrderMapper.toOrderResponse(order);
    }

    @Override
    public void reserveStock(String orderNumber, List<OrderItem> orderItems) {
        inventoryManagementService.reserveOrderInventory(orderNumber, orderItems);

        orderItems.forEach(item -> {
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product != null
                    && product.getLowStockThreshold() != null
                    && product.getStockQuantity() <= product.getLowStockThreshold()) {
                log.info("{} is low on stock, remaining items: {}", product.getName(), product.getStockQuantity());
            }
        });
    }

    @Override
    public void releaseStock(String orderNumber) {
        inventoryManagementService.releaseOrderInventory(orderNumber);
    }

    @Override
    public void finalizeReservedStock(Order order) {
        inventoryManagementService.finalizeReservedOrder(order);
    }


    // HELPER METHODS
    private Order getOrder(String orderNumber){
        User user = authUserUtil.getCurrentUser();
        String cleanOrderNumber = orderNumber.trim().toUpperCase();
        Order order;
        if(user.getRoles() == UserRole.ADMIN){
            order = orderRepository.findByOrderNumber(cleanOrderNumber)
                    .orElseThrow(() -> new ResourceNotFoundException(ORDER_NOT_FOUND));

        }else{
            order = orderRepository.findByOrderNumberAndUser(cleanOrderNumber,user)
                    .orElseThrow(() -> new ResourceNotFoundException(ORDER_NOT_FOUND));
        }
        return order;
    }

    private List<OrderItem> createOrderItems(Cart cart, Order newOrder, ShopWideDiscount activeShop, LocalDateTime pricingTime) {
    return cart.getItems().stream()
            .map(item -> OrderItemMapper.toOrderItem(item, newOrder,
                    effectivePriceService.compute(item.getProduct(), activeShop, pricingTime)))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
}

    private void validateCartItemsForEcommerce(Cart cart) {
        boolean hasUnavailableProduct = cart.getItems().stream()
                .map(CartItem::getProduct)
                .anyMatch(product -> !Boolean.TRUE.equals(product.getIsActive())
                        || !Boolean.TRUE.equals(product.getIsEnlisted()));

        if (hasUnavailableProduct) {
            throw new BadRequestException(PRODUCT_UNAVAILABLE);
        }
    }
}
