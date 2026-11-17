package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.checkout.CheckoutResponse;
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
    @Mock
    private UserRepository userRepository;
    @Mock
    private CheckoutCallbackUrlProvider callbackUrlProvider;
    @Mock
    private OrderLifecycleService lifecycleService;
    @Mock
    private CouponRedemptionService couponRedemptionService;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.mockito.Spy
    private TransientRetry transientRetry = new TransientRetry(0);

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

        lenient().when(userRepository.lockById(1L)).thenReturn(Optional.of(testUser));
        // the real pricing code path, over the mocked collaborators
        org.springframework.test.util.ReflectionTestUtils.setField(orderService, "pricingService",
                new CheckoutPricingService(effectivePriceService, couponRepository, new CouponPerUserLimit(orderRepository), discountCalculationUtil, taxService));
        org.springframework.test.util.ReflectionTestUtils.setField(orderService, "expiryHours", 1L);
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

        CheckoutResponse result = orderService.checkout(null, null, null);

        assertNotNull(result);
        verify(orderRepository, atLeastOnce()).save(any(Order.class));
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        // API-05: Paystack first, then the (row-locking) stock reservation as the last step of the transaction
        org.mockito.InOrder inOrder = inOrder(gatewayAPIService, inventoryManagementService);
        inOrder.verify(gatewayAPIService).initiatePaystackTransaction(any());
        inOrder.verify(inventoryManagementService).reserveOrderInventory(eq("ORD-123"), anyList());
    }

    @Test
    void checkout_StockVisiblyShortIsRefusedBeforePaystack() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        when(taxService.calculateTaxes(any())).thenReturn(new TaxCalculationResult(new ArrayList<>(),
                new Money(BigDecimal.ZERO, CurrencyCode.GHS), new Money(new BigDecimal("200.00"), CurrencyCode.GHS)));
        when(orderNumberGenerator.generateOrderNumber()).thenReturn("ORD-123");
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        testProduct.setStockQuantity(1); // cart wants 2

        BadRequestException e = assertThrows(BadRequestException.class, () -> orderService.checkout(null, null, null));
        assertEquals("Insufficient stock for: Perfume A", e.getMessage());
        verify(gatewayAPIService, never()).initiatePaystackTransaction(any());
        verify(inventoryManagementService, never()).reserveOrderInventory(anyString(), anyList());
    }

    @Test
    void checkout_Success_WithCoupon() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        CouponRedemptionService.Reservation reservation = new CouponRedemptionService.Reservation(testCoupon.getId(), null);
        when(couponRedemptionService.reserve("DISCOUNT10")).thenReturn(reservation);
        when(couponRepository.findById(testCoupon.getId())).thenReturn(Optional.of(testCoupon));
        when(discountCalculationUtil.calculateDiscount(eq(testCoupon), any(BigDecimal.class))).thenReturn(new BigDecimal("20.00"));

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

        CheckoutResponse result = orderService.checkout("DISCOUNT10", null, null);

        assertNotNull(result);
        // the atomic reservation is the active/window/limit check: not repeated (it may just have switched it off)
        verify(discountCalculationUtil, never()).checkIfCouponIsValid(any());
        verify(discountCalculationUtil).calculateDiscount(eq(testCoupon), any(BigDecimal.class));
        // the order holds the reservation: it is not given back
        verify(couponRedemptionService, never()).releaseReservation(any());
    }

    @Test
    void checkout_Failure_NoDeliveryAddress() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> orderService.checkout(null, null, null));
    }

    @Test
    void checkout_Failure_EmptyCart() {
        testCart.setItems(Collections.emptyList());
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        assertThrows(BadRequestException.class, () -> orderService.checkout(null, null, null));
    }

    @Test
    void checkout_Failure_InvalidCoupon() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        CouponRedemptionService.Reservation refused = new CouponRedemptionService.Reservation(null,
                new com.example.perfume_budget.exception.CouponRejectedException(
                        com.example.perfume_budget.exception.CouponRejectedException.Reason.NOT_FOUND, "Invalid coupon code."));
        when(couponRedemptionService.reserve("INVALID")).thenReturn(refused);

        assertThrows(BadRequestException.class, () -> orderService.checkout("INVALID", null, null));
        verify(couponRedemptionService).releaseReservation(refused);
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

        assertThrows(PaymentException.class, () -> orderService.checkout(null, null, null));
        // API-05: stock is reserved only after Paystack answered, so a failed initialisation reserved (and releases) none
        verify(inventoryManagementService, never()).reserveOrderInventory(anyString(), anyList());
        verify(inventoryManagementService, never()).releaseOrderInventory(anyString());
    }

    @Test
    void checkout_PaymentFailureWithCoupon_givesTheReservationBack() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        CouponRedemptionService.Reservation reservation = new CouponRedemptionService.Reservation(testCoupon.getId(), null);
        when(couponRedemptionService.reserve("DISCOUNT10")).thenReturn(reservation);
        when(couponRepository.findById(testCoupon.getId())).thenReturn(Optional.of(testCoupon));
        when(discountCalculationUtil.calculateDiscount(eq(testCoupon), any(BigDecimal.class))).thenReturn(new BigDecimal("20.00"));
        when(taxService.calculateTaxes(any())).thenReturn(new TaxCalculationResult(new ArrayList<>(),
                new Money(BigDecimal.ZERO, CurrencyCode.GHS), new Money(new BigDecimal("180.00"), CurrencyCode.GHS)));
        when(orderNumberGenerator.generateOrderNumber()).thenReturn("ORD-123");
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(gatewayAPIService.initiatePaystackTransaction(any())).thenThrow(new PaymentException("Payment failed"));

        assertThrows(PaymentException.class, () -> orderService.checkout("DISCOUNT10", null, null));
        verify(couponRedemptionService, times(1)).releaseReservation(reservation);
    }

    @Test
    void checkout_IdempotentReplay_neverReservesTheCoupon() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        testOrder.setStatus(com.example.perfume_budget.enums.PaymentStatus.INITIATED);
        testOrder.setCreatedAt(java.time.LocalDateTime.now());
        testOrder.setRequestFingerprint(com.example.perfume_budget.utils.CheckoutFingerprint.of(testCart,
                "DISCOUNT10", testDeliveryDetail.getId()));
        when(orderRepository.findByUserAndIdempotencyKey(testUser, "key-replay-0001")).thenReturn(Optional.of(testOrder));
        Payment paid = Payment.builder().authorizationUrl("url").providerPaymentReference("ref").accessCode("c").build();
        when(paymentRepository.findByOrder_OrderNumber(testOrder.getOrderNumber())).thenReturn(Optional.of(paid));

        CheckoutResponse r = orderService.checkout("DISCOUNT10", null, "key-replay-0001");

        assertTrue(r.idempotentReplay());
        // API-04 rework 2: the pre-check sees the replay, so the counter is never touched (no transient reservation)
        verify(couponRedemptionService, never()).reserve(any());
        verify(couponRedemptionService, never()).releaseReservation(any());
        verify(gatewayAPIService, never()).initiatePaystackTransaction(any());
    }

    // ---- API-04 rework 2: refusals that do not depend on the usage counter are decided BEFORE reserving

    private void stubCouponCheckoutBasics() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        lenient().when(taxService.calculateTaxes(any())).thenReturn(new TaxCalculationResult(new ArrayList<>(),
                new Money(BigDecimal.ZERO, CurrencyCode.GHS), new Money(new BigDecimal("180.00"), CurrencyCode.GHS)));
    }

    @Test
    void checkout_PerUserLimitRefusedBeforeReserving() {
        stubCouponCheckoutBasics();
        testCoupon.setPerUserUsageLimit(1);
        when(couponRepository.findByCode("DISCOUNT10")).thenReturn(Optional.of(testCoupon));
        when(discountCalculationUtil.redeemableIgnoringUsage(testCoupon)).thenReturn(true);
        when(orderRepository.countCouponRedemptionsByUser(1L, 1L)).thenReturn(1L);

        com.example.perfume_budget.exception.CouponRejectedException e = assertThrows(
                com.example.perfume_budget.exception.CouponRejectedException.class,
                () -> orderService.checkout(" discount10 ", null, null));
        assertEquals(com.example.perfume_budget.exception.CouponRejectedException.Reason.PER_USER_LIMIT, e.getReason());
        verify(couponRedemptionService, never()).reserve(any());
        verify(userRepository, never()).lockById(any());
    }

    @Test
    void checkout_MinimumAmountRefusedBeforeReserving() {
        stubCouponCheckoutBasics();
        when(couponRepository.findByCode("DISCOUNT10")).thenReturn(Optional.of(testCoupon));
        when(discountCalculationUtil.redeemableIgnoringUsage(testCoupon)).thenReturn(true);
        when(discountCalculationUtil.calculateDiscount(eq(testCoupon), any(BigDecimal.class))).thenThrow(
                new com.example.perfume_budget.exception.CouponRejectedException(
                        com.example.perfume_budget.exception.CouponRejectedException.Reason.MIN_AMOUNT, "min"));

        assertThrows(BadRequestException.class, () -> orderService.checkout("DISCOUNT10", null, null));
        verify(couponRedemptionService, never()).reserve(any());
    }

    @Test
    void checkout_ACouponOutsideItsWindowIsLeftToTheReservation() {
        stubCouponCheckoutBasics();
        testCoupon.setPerUserUsageLimit(1);
        when(couponRepository.findByCode("DISCOUNT10")).thenReturn(Optional.of(testCoupon));
        when(discountCalculationUtil.redeemableIgnoringUsage(testCoupon)).thenReturn(false);
        CouponRedemptionService.Reservation refused = new CouponRedemptionService.Reservation(1L,
                new com.example.perfume_budget.exception.CouponRejectedException(
                        com.example.perfume_budget.exception.CouponRejectedException.Reason.EXPIRED, "Coupon has expired"));
        when(couponRedemptionService.reserve("DISCOUNT10")).thenReturn(refused);

        BadRequestException e = assertThrows(BadRequestException.class, () -> orderService.checkout("DISCOUNT10", null, null));
        assertEquals("Coupon has expired", e.getMessage());
        verify(orderRepository, never()).countCouponRedemptionsByUser(any(), any());
    }

    @Test
    void checkout_AReplayThePreCheckSawButThatIsGoneRunsAgainWithAReservation() {
        stubCouponCheckoutBasics();
        Order open = Order.builder().id(9L).orderNumber("ORD-OPEN").status(PaymentStatus.INITIATED)
                .requestFingerprint(com.example.perfume_budget.utils.CheckoutFingerprint.of(testCart, "DISCOUNT10",
                        testDeliveryDetail.getId()))
                .totalAmount(new Money(new BigDecimal("180.00"), CurrencyCode.GHS)).build();
        when(orderRepository.findOpenByUser(eq(testUser), any()))
                .thenReturn(List.of(open))   // pre-check: an open order to replay
                .thenReturn(List.of());       // checkout transaction: it was cancelled in between
        when(paymentRepository.findByOrder_OrderNumber("ORD-OPEN")).thenReturn(Optional.of(
                Payment.builder().authorizationUrl("url").providerPaymentReference("ref").accessCode("c").build()));
        CouponRedemptionService.Reservation reservation = new CouponRedemptionService.Reservation(testCoupon.getId(), null);
        when(couponRedemptionService.reserve("DISCOUNT10")).thenReturn(reservation);
        when(couponRepository.findById(testCoupon.getId())).thenReturn(Optional.of(testCoupon));
        when(discountCalculationUtil.calculateDiscount(eq(testCoupon), any(BigDecimal.class))).thenReturn(new BigDecimal("20.00"));
        when(orderNumberGenerator.generateOrderNumber()).thenReturn("ORD-123");
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(gatewayAPIService.initiatePaystackTransaction(any())).thenReturn(PaystackInitiateTransactionResponse.builder()
                .status(true).data(new PaystackInitiateTransactionResponse.Data("url2", "code2", "ref2")).build());

        CheckoutResponse r = orderService.checkout("DISCOUNT10", null, null);

        assertFalse(r.idempotentReplay());
        verify(couponRedemptionService, times(1)).reserve("DISCOUNT10");
        verify(couponRedemptionService, never()).releaseReservation(any());
        verify(userRepository, times(2)).lockById(1L); // the first run stopped before pricing and rolled back
    }

    private Order openOrder(String number, String fingerprint) {
        return Order.builder().id(9L).orderNumber(number).status(PaymentStatus.INITIATED)
                .requestFingerprint(fingerprint).createdAt(java.time.LocalDateTime.now())
                .totalAmount(new Money(new BigDecimal("180.00"), CurrencyCode.GHS)).build();
    }

    @Test
    void checkout_PAY03_NewKeySameCartReusesTheOpenOrderWithoutReservingTheCoupon() {
        stubCouponCheckoutBasics();
        Order open = openOrder("ORD-OPEN", CheckoutFingerprint.of(testCart, "DISCOUNT10", testDeliveryDetail.getId()));
        when(orderRepository.findOpenByUser(eq(testUser), any())).thenReturn(List.of(open));
        when(paymentRepository.findByOrder_OrderNumber("ORD-OPEN")).thenReturn(Optional.of(
                Payment.builder().authorizationUrl("url").providerPaymentReference("ref").accessCode("c").build()));

        CheckoutResponse r = orderService.checkout("discount10", null, "another-tab-key-1");

        assertTrue(r.idempotentReplay());
        assertEquals("ORD-OPEN", r.orderNumber());
        verify(couponRedemptionService, never()).reserve(any());
        verifyNoInteractions(gatewayAPIService);
        verify(orderRepository, never()).save(any());
        verify(userRepository).lockById(1L);
    }

    @Test
    void checkout_PAY03_DifferentCartIsRefusedBeforeReservingTheCoupon() {
        stubCouponCheckoutBasics();
        Order open = openOrder("ORD-OPEN", CheckoutFingerprint.of(testCart, "", testDeliveryDetail.getId()));
        when(orderRepository.findOpenByUser(eq(testUser), any())).thenReturn(List.of(open));

        com.example.perfume_budget.exception.ApiCodeException e = assertThrows(
                com.example.perfume_budget.exception.ApiCodeException.class,
                () -> orderService.checkout("DISCOUNT10", null, "another-tab-key-2"));
        assertEquals("OPEN_ORDER_EXISTS", e.getCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getStatus());
        assertEquals("ORD-OPEN", e.body().get("orderNumber"));
        verify(couponRedemptionService, never()).reserve(any());
        verifyNoInteractions(gatewayAPIService);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void checkout_PAY03_DifferentCartWithoutCouponIsRefusedUnderTheCustomerLock() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);
        Order open = openOrder("ORD-OPEN", "a-different-fingerprint");
        when(orderRepository.findOpenByUser(eq(testUser), any())).thenReturn(List.of(open));

        com.example.perfume_budget.exception.ApiCodeException e = assertThrows(
                com.example.perfume_budget.exception.ApiCodeException.class,
                () -> orderService.checkout(null, null, "another-tab-key-3"));
        assertEquals("OPEN_ORDER_EXISTS", e.getCode());
        verify(userRepository).lockById(1L);
        verifyNoInteractions(gatewayAPIService);
    }

    @Test
    void checkout_Failure_ProductNotEnlisted() {
        testProduct.setIsEnlisted(false);
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByUserAndIsDefaultTrue(testUser)).thenReturn(Optional.of(testDeliveryDetail));
        when(cartUtil.getCurrentUserCart()).thenReturn(testCart);

        com.example.perfume_budget.exception.ApiCodeException e = assertThrows(
                com.example.perfume_budget.exception.ApiCodeException.class, () -> orderService.checkout(null, null, null));
        assertEquals("PRODUCT_UNAVAILABLE", e.getCode());
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("One or more products in your cart are no longer available for ecommerce purchase.", e.getMessage());
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
    void updateOrderStatus_DelegatesToLifecycleWithActorAndVersion() {
        testUser.setRoles(UserRole.ADMIN);
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(lifecycleService.adminUpdateStatus("ORD-123", OrderProcessingStatus.PACKING, 3L, testUser.getEmail()))
                .thenReturn(new OrderLifecycleService.AdminUpdate(testOrder, null));

        OrderResponse result = orderService.updateOrderStatus("ORD-123",
                new OrderStatusUpdateRequest(OrderProcessingStatus.PACKING, 3L), null);

        assertEquals("ORD-123", result.orderNumber());
        assertNotNull(result.account());
    }

    @Test
    void updateOrderStatus_BodyAndIfMatchDisagreeingIs400() {
        var e = assertThrows(com.example.perfume_budget.exception.ApiCodeException.class, () -> orderService.updateOrderStatus(
                "ORD-123", new OrderStatusUpdateRequest(OrderProcessingStatus.PACKING, 3L), 4L));
        assertEquals("VERSION_MISMATCH", e.getCode());
        verifyNoInteractions(lifecycleService);
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
