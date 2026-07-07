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
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.*;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.PaymentGatewayAPIService;
import com.example.perfume_budget.service.interfaces.TaxService;
import com.example.perfume_budget.utils.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private CartUtil cartUtil;
    @Mock
    private CouponRepository couponRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderNumberGenerator orderNumberGenerator;
    @Mock
    private PaymentReferenceGenerator paymentReferenceGenerator;
    @Mock
    private PaymentGatewayAPIService gatewayAPIService;
    @Mock
    private DeliveryDetailRepository deliveryDetailRepository;
    @Mock
    private TaxService taxService;
    @Mock
    private InventoryManagementService inventoryManagementService;
    @Mock
    private DiscountCalculationUtil discountCalculationUtil;
    @Mock
    private EffectivePriceService effectivePriceService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private User testUser;
    private Cart testCart;
    private Product testProduct;
    private Coupon testCoupon;
    private Order testOrder;
    private DeliveryDetail testDeliveryDetail;

    @BeforeEach
    void setUp() {
        testDeliveryDetail = DeliveryDetail.builder().id(1L).isDefault(true).build();

        testUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .fullName("Test User")
                .roles(UserRole.CUSTOMER)
                .isActive(true)
                .deliveryAddresses(new ArrayList<>(List.of(testDeliveryDetail)))
                .build();
        testDeliveryDetail.setUser(testUser);

        testProduct = Product.builder()
                .id(1L)
                .name("Perfume A")
                .price(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .stockQuantity(10)
                .isActive(true)
                .isEnlisted(true)
                .build();

        CartItem cartItem = CartItem.builder()
                .id(1L)
                .product(testProduct)
                .quantity(2)
                .build();

        testCart = Cart.builder()
                .id(1L)
                .user(testUser)
                .items(new ArrayList<>(List.of(cartItem)))
                .build();

        testCoupon = Coupon.builder()
                .id(1L)
                .code("DISCOUNT10")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("10.00"))
                .isActive(true)
                .startDate(LocalDate.now().minusDays(1))
                .expirationDate(LocalDate.now().plusDays(1))
                .usageLimit(10)
                .usageCount(0)
                .build();

        testOrder = Order.builder()
                .id(1L)
                .orderNumber("ORD-123")
                .user(testUser)
                .subtotal(new Money(new BigDecimal("200.00"), CurrencyCode.GHS))
                .discountAmount(new Money(new BigDecimal("20.00"), CurrencyCode.GHS))
                .totalAmount(new Money(new BigDecimal("180.00"), CurrencyCode.GHS))
                .status(PaymentStatus.PENDING)
                .deliveryStatus(OrderProcessingStatus.PENDING)
                .items(new ArrayList<>())
                .taxes(new ArrayList<>())
                .totalTaxAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .build();

        OrderItem orderItem = OrderItem.builder()
                .id(1L)
                .productId(1L)
                .productName("Perfume A")
                .quantity(2)
                .unitPrice(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .totalPrice(new Money(new BigDecimal("200.00"), CurrencyCode.GHS))
                .order(testOrder)
                .build();
        testOrder.getItems().add(orderItem);

        // By default no product/shop discount: effective price echoes the product's base price.
        lenient().when(effectivePriceService.activeShopDiscount()).thenReturn(Optional.empty());
        lenient().when(effectivePriceService.now()).thenReturn(java.time.LocalDateTime.now());
        lenient().when(effectivePriceService.compute(any(Product.class), any(), any()))
                .thenAnswer(invocation -> {
                    Product p = invocation.getArgument(0);
                    BigDecimal amount = p.getPrice().getAmount();
                    return new EffectivePrice(amount, amount, p.getPrice().getCurrencyCode(),
                            false, DiscountSource.NONE, null, BigDecimal.ZERO, null);
                });
    }

    @Test
    void checkout_Success_NoCoupon() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        TaxCalculationResult mockTaxResult = new TaxCalculationResult(
                new ArrayList<>(),
                new Money(BigDecimal.ZERO, CurrencyCode.GHS),
                new Money(new BigDecimal("200.00"), CurrencyCode.GHS)
        );
        when(taxService.calculateTaxes(any())).thenReturn(mockTaxResult);

        when(orderNumberGenerator.generateOrderNumber()).thenReturn("ORD-123");
        when(paymentReferenceGenerator.generateReference()).thenReturn("REF-123");
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        PaystackInitiateTransactionResponse mockResponse = PaystackInitiateTransactionResponse.builder()
                .status(true)
                .data(new PaystackInitiateTransactionResponse.Data("url", "code", "ref"))
                .build();
        when(gatewayAPIService.initiatePaystackTransaction(any())).thenReturn(mockResponse);

        PaystackInitiateTransactionResponse result = orderService.checkout(null);

        assertNotNull(result);
        verify(orderRepository, atLeastOnce()).save(any(Order.class));
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(gatewayAPIService).initiatePaystackTransaction(any());
    }

    @Test
    void checkout_Success_WithCoupon() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(couponRepository.findByCode("DISCOUNT10")).thenReturn(Optional.of(testCoupon));
        doNothing().when(discountCalculationUtil).checkIfCouponIsValid(testCoupon);
        when(discountCalculationUtil.applyDiscount(eq(testCoupon), any(BigDecimal.class))).thenReturn(new BigDecimal("180.00"));

        TaxCalculationResult mockTaxResult = new TaxCalculationResult(
                new ArrayList<>(),
                new Money(BigDecimal.ZERO, CurrencyCode.GHS),
                new Money(new BigDecimal("180.00"), CurrencyCode.GHS)
        );
        when(taxService.calculateTaxes(any())).thenReturn(mockTaxResult);

        when(orderNumberGenerator.generateOrderNumber()).thenReturn("ORD-123");
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        PaystackInitiateTransactionResponse mockResponse = PaystackInitiateTransactionResponse.builder()
                .status(true)
                .data(new PaystackInitiateTransactionResponse.Data("url", "code", "ref"))
                .build();
        when(gatewayAPIService.initiatePaystackTransaction(any())).thenReturn(mockResponse);

        PaystackInitiateTransactionResponse result = orderService.checkout("DISCOUNT10");

        assertNotNull(result);
        verify(discountCalculationUtil).checkIfCouponIsValid(testCoupon);
        verify(discountCalculationUtil).applyDiscount(eq(testCoupon), any(BigDecimal.class));
    }

    @Test
    void checkout_Failure_NoDeliveryAddress() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> orderService.checkout(null));
    }

    @Test
    void checkout_Failure_EmptyCart() {
        testCart.setItems(Collections.emptyList());
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        assertThrows(BadRequestException.class, () -> orderService.checkout(null));
    }

    @Test
    void checkout_Failure_InvalidCoupon() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(couponRepository.findByCode("INVALID")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> orderService.checkout("INVALID"));
    }

    @Test
    void checkout_Failure_PaymentException() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        TaxCalculationResult mockTaxResult = new TaxCalculationResult(
                new ArrayList<>(),
                new Money(BigDecimal.ZERO, CurrencyCode.GHS),
                new Money(new BigDecimal("200.00"), CurrencyCode.GHS)
        );
        when(taxService.calculateTaxes(any())).thenReturn(mockTaxResult);

        when(orderNumberGenerator.generateOrderNumber()).thenReturn("ORD-123");
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(gatewayAPIService.initiatePaystackTransaction(any())).thenThrow(new PaymentException("Payment failed"));

        assertThrows(PaymentException.class, () -> orderService.checkout(null));
        verify(inventoryManagementService).releaseOrderInventory("ORD-123");
    }

    @Test
    void checkout_Failure_ProductNotEnlisted() {
        testProduct.setIsEnlisted(false);
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        assertThrows(BadRequestException.class, () -> orderService.checkout(null));
        verifyNoInteractions(orderRepository, paymentRepository, gatewayAPIService);
    }

    @Test
    void getMyOrders_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        Page<Order> orderPage = new PageImpl<>(List.of(testOrder));
        when(orderRepository.findAllByUser(any(Pageable.class), eq(testUser))).thenReturn(orderPage);

        PageResponse<OrderListResponse> result = orderService.getMyOrders(Pageable.unpaged());

        assertNotNull(result);
        assertEquals(1, result.content().size());
    }

    @Test
    void getOrderByOrderNumber_Success_Admin() {
        testUser.setRoles(UserRole.ADMIN);
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(orderRepository.findByOrderNumber("ORD-123")).thenReturn(Optional.of(testOrder));

        OrderResponse result = orderService.getOrderByOrderNumber("ORD-123");

        assertNotNull(result);
        assertEquals("ORD-123", result.orderNumber());
    }

    @Test
    void updateOrderStatus_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser); // for getOrder
        testUser.setRoles(UserRole.ADMIN);
        when(orderRepository.findByOrderNumber("ORD-123")).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        OrderStatusUpdateRequest updateRequest = new OrderStatusUpdateRequest(OrderProcessingStatus.PACKING);
        OrderResponse result = orderService.updateOrderStatus("ORD-123", updateRequest);

        assertNotNull(result);
        verify(eventPublisher).publishEvent(any(OrderStatusChangeEvent.class));
    }

    @Test
    void updateOrderStatus_Failure_AlreadyDelivered() {
        testOrder.setDeliveryStatus(OrderProcessingStatus.DELIVERED);
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        testUser.setRoles(UserRole.ADMIN);
        when(orderRepository.findByOrderNumber("ORD-123")).thenReturn(Optional.of(testOrder));

        assertThrows(BadRequestException.class, () -> orderService.updateOrderStatus("ORD-123", new OrderStatusUpdateRequest(OrderProcessingStatus.PENDING)));
    }

    @Test
    void reserveStock_DelegatesToInventoryManagement() {
        testProduct.setStockQuantity(1);
        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setQuantity(2);

        orderService.reserveStock("ORD-123", List.of(item));

        verify(inventoryManagementService).reserveOrderInventory("ORD-123", List.of(item));
    }

    @Test
    void releaseStock_DelegatesToInventoryManagement() {
        orderService.releaseStock("ORD-123");

        verify(inventoryManagementService).releaseOrderInventory("ORD-123");
    }
}
