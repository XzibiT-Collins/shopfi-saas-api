package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.dto.walk_in.request.WalkInCustomerRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderItemRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ForbiddenException;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.repository.WalkInCustomerRepository;
import com.example.perfume_budget.repository.WalkInOrderRepository;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.TaxService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.WalkInOrderNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalkInOrderServiceImplTest {

    @Mock
    private WalkInOrderRepository walkInOrderRepository;
    @Mock
    private WalkInCustomerRepository walkInCustomerRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private TaxService taxService;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private WalkInOrderNumberGenerator walkInOrderNumberGenerator;
    @Mock
    private BookkeepingService bookkeepingService;
    @Mock
    private InventoryManagementService inventoryManagementService;
    @Mock
    private FrontDeskAccessService frontDeskAccessService;
    @Mock
    private EffectivePriceService effectivePriceService;

    @InjectMocks
    private WalkInOrderServiceImpl walkInOrderService;

    private User adminUser;
    private User frontDeskUser;
    private User registeredCustomer;
    private Product product;

    @BeforeEach
    void setUp() {
        adminUser = User.builder()
                .id(10L)
                .fullName("Admin User")
                .email("admin@example.com")
                .roles(UserRole.ADMIN)
                .build();
        frontDeskUser = User.builder()
                .id(11L)
                .fullName("Front Desk User")
                .email("frontdesk@example.com")
                .roles(UserRole.FRONT_DESK)
                .build();

        registeredCustomer = User.builder()
                .id(20L)
                .fullName("Jane Customer")
                .email("jane@example.com")
                .roles(UserRole.CUSTOMER)
                .profile(Profile.builder().phoneNumber("0240000000").build())
                .build();

        product = Product.builder()
                .id(1L)
                .name("Perfume A")
                .sku("SKU-1")
                .price(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .costPrice(new Money(new BigDecimal("60.00"), CurrencyCode.GHS))
                .stockQuantity(10)
                .soldCount(1)
                .isActive(true)
                .build();

        // By default no product/shop discount: effective price echoes the product's base price.
        lenient().when(effectivePriceService.activeShopDiscount()).thenReturn(Optional.empty());
        lenient().when(effectivePriceService.now()).thenReturn(LocalDateTime.now());
        lenient().when(effectivePriceService.compute(any(Product.class), any(), any()))
                .thenAnswer(invocation -> {
                    Product p = invocation.getArgument(0);
                    BigDecimal amount = p.getPrice().getAmount();
                    return new EffectivePrice(amount, amount, p.getPrice().getCurrencyCode(),
                            false, DiscountSource.NONE, null, BigDecimal.ZERO, null);
                });
    }

    @Test
    void placeWalkInOrder_Success_CashAnonymous() {
        WalkInCustomer savedCustomer = WalkInCustomer.builder()
                .id(1L)
                .name("Cash Buyer")
                .phone("0200000000")
                .build();
        WalkInOrderRequest request = new WalkInOrderRequest(
                null,
                new WalkInCustomerRequest("Cash Buyer", "0200000000", null),
                List.of(new WalkInOrderItemRequest(1L, 2)),
                null,
                null,
                WalkInPaymentMethod.CASH,
                new BigDecimal("220.00"),
                null,
                null
        );

        TaxCalculationResult taxResult = TaxCalculationResult.builder()
                .orderTaxes(List.of(
                        OrderTax.builder()
                                .taxName("VAT")
                                .taxCode("VAT")
                                .taxRate(new BigDecimal("10.00"))
                                .taxableAmount(new Money(new BigDecimal("200.00"), CurrencyCode.GHS))
                                .taxAmount(new Money(new BigDecimal("20.00"), CurrencyCode.GHS))
                                .build()
                ))
                .totalTaxAmount(new Money(new BigDecimal("20.00"), CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(new BigDecimal("220.00"), CurrencyCode.GHS))
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(walkInCustomerRepository.save(any(WalkInCustomer.class))).thenReturn(savedCustomer);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(taxService.calculateTaxes(new BigDecimal("200.00"))).thenReturn(taxResult);
        when(walkInOrderNumberGenerator.generateOrderNumber()).thenReturn("WLK-20260328120000-ABCDEFGH");
        when(walkInOrderRepository.save(any(WalkInOrder.class))).thenAnswer(invocation -> {
            WalkInOrder order = invocation.getArgument(0);
            order.setId(100L);
            order.setCreatedAt(LocalDateTime.of(2026, 3, 28, 12, 0));
            return order;
        });

        WalkInOrderResponse response = walkInOrderService.placeWalkInOrder(request);

        assertNotNull(response);
        assertEquals("WLK-20260328120000-ABCDEFGH", response.orderNumber());
        assertEquals("Cash Buyer", response.customerName());
        assertEquals("GHS 220.00", response.totalAmount());
        assertEquals("GHS 0.00", response.changeGiven());
        verify(inventoryManagementService).consumeWalkInInventory(eq("WLK-20260328120000-ABCDEFGH"), any());
        verify(productRepository).incrementSoldCount(1L, 2);
        verify(bookkeepingService).recordWalkInSale(any(WalkInOrder.class));
    }

    @Test
    void placeWalkInOrder_Success_SplitPaymentRegisteredCustomer() {
        WalkInOrderRequest request = new WalkInOrderRequest(
                20L,
                null,
                List.of(new WalkInOrderItemRequest(1L, 1)),
                null,
                null,
                WalkInPaymentMethod.SPLIT,
                new BigDecimal("110.00"),
                new BigDecimal("50.00"),
                new BigDecimal("60.00")
        );

        TaxCalculationResult taxResult = TaxCalculationResult.builder()
                .orderTaxes(List.of())
                .totalTaxAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(userRepository.findById(20L)).thenReturn(Optional.of(registeredCustomer));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(taxService.calculateTaxes(new BigDecimal("100.00"))).thenReturn(taxResult);
        when(walkInOrderNumberGenerator.generateOrderNumber()).thenReturn("WLK-20260328120500-HGFEDCBA");
        when(walkInOrderRepository.save(any(WalkInOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WalkInOrderResponse response = walkInOrderService.placeWalkInOrder(request);

        assertEquals("Jane Customer", response.customerName());
        assertEquals("0240000000", response.customerPhone());
        assertEquals(WalkInPaymentMethod.SPLIT, response.paymentMethod());
        assertEquals("GHS 0.00", response.changeGiven());
    }

    @Test
    void placeWalkInOrder_Failure_InsufficientCashPayment() {
        WalkInCustomer savedCustomer = WalkInCustomer.builder().id(1L).build();
        WalkInOrderRequest request = new WalkInOrderRequest(
                null,
                new WalkInCustomerRequest("Buyer", null, null),
                List.of(new WalkInOrderItemRequest(1L, 1)),
                null,
                null,
                WalkInPaymentMethod.CASH,
                new BigDecimal("90.00"),
                null,
                null
        );

        TaxCalculationResult taxResult = TaxCalculationResult.builder()
                .orderTaxes(List.of())
                .totalTaxAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(walkInCustomerRepository.save(any(WalkInCustomer.class))).thenReturn(savedCustomer);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(taxService.calculateTaxes(new BigDecimal("100.00"))).thenReturn(taxResult);

        assertThrows(BadRequestException.class, () -> walkInOrderService.placeWalkInOrder(request));
        verify(walkInOrderRepository, never()).save(any());
    }

    @Test
    void placeWalkInOrder_Success_PercentageDiscount() {
        WalkInCustomer savedCustomer = WalkInCustomer.builder().id(1L).name("Buyer").build();
        WalkInOrderRequest request = new WalkInOrderRequest(
                null,
                new WalkInCustomerRequest("Buyer", null, null),
                List.of(new WalkInOrderItemRequest(1L, 2)),
                DiscountType.PERCENTAGE,
                new BigDecimal("10.00"),
                WalkInPaymentMethod.CASH,
                new BigDecimal("198.00"),
                null,
                null
        );

        TaxCalculationResult taxResult = TaxCalculationResult.builder()
                .orderTaxes(List.of())
                .totalTaxAmount(new Money(new BigDecimal("18.00"), CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(new BigDecimal("198.00"), CurrencyCode.GHS))
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(walkInCustomerRepository.save(any(WalkInCustomer.class))).thenReturn(savedCustomer);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(taxService.calculateTaxes(new BigDecimal("180.00"))).thenReturn(taxResult);
        when(walkInOrderNumberGenerator.generateOrderNumber()).thenReturn("WLK-DISCOUNT-001");
        when(walkInOrderRepository.save(any(WalkInOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WalkInOrderResponse response = walkInOrderService.placeWalkInOrder(request);

        assertEquals(DiscountType.PERCENTAGE, response.discountType());
        assertEquals("GHS 10.00", response.discountValue());
        assertEquals("GHS 20.00", response.discountAmount());
        assertEquals("GHS 198.00", response.totalAmount());
    }

    @Test
    void placeWalkInOrder_Success_FlatDiscountCappedAtSubtotal() {
        WalkInCustomer savedCustomer = WalkInCustomer.builder().id(1L).name("Buyer").build();
        WalkInOrderRequest request = new WalkInOrderRequest(
                null,
                new WalkInCustomerRequest("Buyer", null, null),
                List.of(new WalkInOrderItemRequest(1L, 1)),
                DiscountType.FLAT,
                new BigDecimal("150.00"),
                WalkInPaymentMethod.CASH,
                new BigDecimal("0.00"),
                null,
                null
        );

        TaxCalculationResult taxResult = TaxCalculationResult.builder()
                .orderTaxes(List.of())
                .totalTaxAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(walkInCustomerRepository.save(any(WalkInCustomer.class))).thenReturn(savedCustomer);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(taxService.calculateTaxes(BigDecimal.ZERO.setScale(2))).thenReturn(taxResult);
        when(walkInOrderNumberGenerator.generateOrderNumber()).thenReturn("WLK-DISCOUNT-002");
        when(walkInOrderRepository.save(any(WalkInOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WalkInOrderResponse response = walkInOrderService.placeWalkInOrder(request);

        assertEquals(DiscountType.FLAT, response.discountType());
        assertEquals("GHS 150.00", response.discountValue());
        assertEquals("GHS 100.00", response.discountAmount());
        assertEquals("GHS 0.00", response.totalAmount());
    }

    @Test
    void placeWalkInOrder_Failure_PartialDiscountPayload() {
        WalkInOrderRequest request = new WalkInOrderRequest(
                20L,
                null,
                List.of(new WalkInOrderItemRequest(1L, 1)),
                DiscountType.FLAT,
                null,
                WalkInPaymentMethod.CASH,
                new BigDecimal("100.00"),
                null,
                null
        );

        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(userRepository.findById(20L)).thenReturn(Optional.of(registeredCustomer));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThrows(BadRequestException.class, () -> walkInOrderService.placeWalkInOrder(request));
        verify(taxService, never()).calculateTaxes(any());
    }

    @Test
    void searchCustomers_ReturnsCustomerMatches() {
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(userRepository.searchUsersByNameOrEmailAndRole("jane", UserRole.CUSTOMER))
                .thenReturn(List.of(registeredCustomer));

        List<CustomerSearchResponse> result = walkInOrderService.searchCustomers("jane");

        assertEquals(1, result.size());
        assertEquals("Jane Customer", result.get(0).fullName());
    }

    @Test
    void getWalkInOrders_WithDateFilter_ReturnsPaginatedResponses() {
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        WalkInOrder order = WalkInOrder.builder()
                .orderNumber("WLK-1")
                .processedBy(adminUser)
                .walkInCustomer(WalkInCustomer.builder().name("Buyer").phone("123").build())
                .discountType(DiscountType.FLAT)
                .discountValue(new BigDecimal("5.00"))
                .paymentMethod(WalkInPaymentMethod.CASH)
                .status(WalkInOrderStatus.COMPLETED)
                .subtotal(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .discountAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .totalTaxAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .totalAmount(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .amountPaid(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .changeGiven(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(walkInOrderRepository.findAllByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));

        PageResponse<WalkInOrderResponse> result = walkInOrderService.getWalkInOrders(LocalDate.now(), Pageable.unpaged());

        assertEquals(1, result.content().size());
        assertEquals("WLK-1", result.content().get(0).orderNumber());
    }

    @Test
    void placeWalkInOrder_Success_ForFrontDeskWithPermission() {
        WalkInCustomer savedCustomer = WalkInCustomer.builder()
                .id(1L)
                .name("Desk Buyer")
                .build();
        WalkInOrderRequest request = new WalkInOrderRequest(
                null,
                new WalkInCustomerRequest("Desk Buyer", null, null),
                List.of(new WalkInOrderItemRequest(1L, 1)),
                null,
                null,
                WalkInPaymentMethod.CASH,
                new BigDecimal("110.00"),
                null,
                null
        );
        TaxCalculationResult taxResult = TaxCalculationResult.builder()
                .orderTaxes(List.of())
                .totalTaxAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(frontDeskUser);
        when(frontDeskAccessService.hasPermission(frontDeskUser, FrontDeskPermission.WALK_IN_ORDER_CREATE)).thenReturn(true);
        when(walkInCustomerRepository.save(any(WalkInCustomer.class))).thenReturn(savedCustomer);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(taxService.calculateTaxes(new BigDecimal("100.00"))).thenReturn(taxResult);
        when(walkInOrderNumberGenerator.generateOrderNumber()).thenReturn("WLK-FRONT-001");
        when(walkInOrderRepository.save(any(WalkInOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WalkInOrderResponse response = walkInOrderService.placeWalkInOrder(request);

        assertEquals("WLK-FRONT-001", response.orderNumber());
    }

    @Test
    void placeWalkInOrder_FailsForFrontDeskWithoutPermission() {
        WalkInOrderRequest request = new WalkInOrderRequest(
                null,
                new WalkInCustomerRequest("Desk Buyer", null, null),
                List.of(new WalkInOrderItemRequest(1L, 1)),
                null,
                null,
                WalkInPaymentMethod.CASH,
                new BigDecimal("110.00"),
                null,
                null
        );

        when(authUserUtil.getCurrentUser()).thenReturn(frontDeskUser);
        when(frontDeskAccessService.hasPermission(frontDeskUser, FrontDeskPermission.WALK_IN_ORDER_CREATE)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> walkInOrderService.placeWalkInOrder(request));
        verify(walkInCustomerRepository, never()).save(any());
    }

    @Test
    void searchCustomers_FailsForFrontDeskWithoutPermission() {
        when(authUserUtil.getCurrentUser()).thenReturn(frontDeskUser);
        when(frontDeskAccessService.hasPermission(frontDeskUser, FrontDeskPermission.CUSTOMER_SEARCH)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> walkInOrderService.searchCustomers("jane"));
    }

    @Test
    void getWalkInOrders_SucceedsForFrontDeskWithViewPermission() {
        WalkInOrder order = WalkInOrder.builder()
                .orderNumber("WLK-2")
                .processedBy(adminUser)
                .walkInCustomer(WalkInCustomer.builder().name("Buyer").build())
                .paymentMethod(WalkInPaymentMethod.CASH)
                .status(WalkInOrderStatus.COMPLETED)
                .subtotal(new Money(new BigDecimal("100.00"), CurrencyCode.GHS))
                .discountAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .totalTaxAmount(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .totalAmount(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .amountPaid(new Money(new BigDecimal("110.00"), CurrencyCode.GHS))
                .changeGiven(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(authUserUtil.getCurrentUser()).thenReturn(frontDeskUser);
        when(frontDeskAccessService.hasPermission(frontDeskUser, FrontDeskPermission.WALK_IN_ORDER_VIEW)).thenReturn(true);
        when(walkInOrderRepository.findAllByOrderByCreatedAtDesc(Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(order)));

        PageResponse<WalkInOrderResponse> result = walkInOrderService.getWalkInOrders(null, Pageable.unpaged());

        assertEquals(1, result.content().size());
        assertEquals("WLK-2", result.content().get(0).orderNumber());
    }

    @Test
    void markReceiptPrinted_FailsForFrontDeskWithoutPermission() {
        when(authUserUtil.getCurrentUser()).thenReturn(frontDeskUser);
        when(frontDeskAccessService.hasPermission(frontDeskUser, FrontDeskPermission.WALK_IN_ORDER_MARK_RECEIPT_PRINTED)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> walkInOrderService.markReceiptPrinted("WLK-1"));
        verify(walkInOrderRepository, never()).findByOrderNumber(any());
    }
}
