package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.dto.walk_in.request.WalkInCustomerRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderItemRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.request.WalkInQuoteRequest;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInQuoteResponse;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.exception.ApiCodeException;
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
import com.example.perfume_budget.utils.WalkInFingerprint;
import com.example.perfume_budget.utils.WalkInOrderNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** WALKIN-BE unit tests: quote is read-only, replay/409 logic, key validation, fingerprint normalisation. */
@ExtendWith(MockitoExtension.class)
class WalkInQuoteIdempotencyUnitTest {

    @Mock private WalkInOrderRepository walkInOrderRepository;
    @Mock private WalkInCustomerRepository walkInCustomerRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductRepository productRepository;
    @Mock private TaxService taxService;
    @Mock private AuthUserUtil authUserUtil;
    @Mock private WalkInOrderNumberGenerator walkInOrderNumberGenerator;
    @Mock private BookkeepingService bookkeepingService;
    @Mock private InventoryManagementService inventoryManagementService;
    @Mock private FrontDeskAccessService frontDeskAccessService;
    @Mock private EffectivePriceService effectivePriceService;
    @InjectMocks private WalkInOrderServiceImpl service;

    private User admin;
    private Product product;

    @BeforeEach
    void setUp() {
        admin = User.builder().id(10L).fullName("Admin").email("admin@example.com").roles(UserRole.ADMIN).build();
        product = Product.builder().id(1L).name("Perfume A").sku("SKU-1")
                .price(new Money(new BigDecimal("100.00"), CurrencyCode.GHS)).stockQuantity(10).isActive(true).build();
        lenient().when(authUserUtil.getCurrentUser()).thenReturn(admin);
        lenient().when(userRepository.lockById(10L)).thenReturn(Optional.of(admin));
        lenient().when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        lenient().when(effectivePriceService.activeShopDiscount()).thenReturn(Optional.empty());
        lenient().when(effectivePriceService.now()).thenReturn(LocalDateTime.now());
        lenient().when(effectivePriceService.compute(any(Product.class), any(), any())).thenAnswer(inv -> {
            BigDecimal a = ((Product) inv.getArgument(0)).getPrice().getAmount();
            return new EffectivePrice(a, a, CurrencyCode.GHS, false, DiscountSource.NONE, null, BigDecimal.ZERO, null);
        });
        lenient().when(taxService.calculateTaxes(any())).thenAnswer(inv -> {
            BigDecimal base = inv.getArgument(0);
            BigDecimal tax = base.multiply(new BigDecimal("0.10")).setScale(2);
            return TaxCalculationResult.builder().orderTaxes(List.of())
                    .totalTaxAmount(new Money(tax, CurrencyCode.GHS))
                    .totalAmountAfterTax(new Money(base.add(tax), CurrencyCode.GHS)).build();
        });
    }

    private WalkInOrderRequest request(int qty, String paid) {
        return new WalkInOrderRequest(null, new WalkInCustomerRequest("Kofi", "0200000000", null),
                List.of(new WalkInOrderItemRequest(1L, qty)), null, null, WalkInPaymentMethod.CASH, new BigDecimal(paid), null, null);
    }

    private WalkInOrder existingOrder(String key, WalkInOrderRequest forRequest) {
        return WalkInOrder.builder().orderNumber("WI-1").processedBy(admin)
                .subtotal(new Money(new BigDecimal("200.00"), CurrencyCode.GHS))
                .discountAmount(new Money(BigDecimal.ZERO, CurrencyCode.GHS))
                .totalTaxAmount(new Money(new BigDecimal("20.00"), CurrencyCode.GHS))
                .totalAmount(new Money(new BigDecimal("220.00"), CurrencyCode.GHS))
                .amountPaid(new Money(new BigDecimal("300.00"), CurrencyCode.GHS))
                .changeGiven(new Money(new BigDecimal("80.00"), CurrencyCode.GHS))
                .paymentMethod(WalkInPaymentMethod.CASH).status(WalkInOrderStatus.COMPLETED).receiptPrinted(false)
                .idempotencyKey(key).requestFingerprint(WalkInFingerprint.of(forRequest)).build();
    }

    @Test
    void quoteWritesNothingAndReturnsTotals() {
        WalkInQuoteResponse q = service.quote(new WalkInQuoteRequest(List.of(new WalkInOrderItemRequest(1L, 2)),
                null, null, WalkInPaymentMethod.CASH, new BigDecimal("300.00")));
        assertThat(q.subtotal()).isEqualTo("GHS 200.00");
        assertThat(q.totalTaxAmount()).isEqualTo("GHS 20.00");
        assertThat(q.totalAmount()).isEqualTo("GHS 220.00");
        assertThat(q.changeGiven()).isEqualTo("GHS 80.00");
        verifyNoInteractions(inventoryManagementService, bookkeepingService, walkInOrderNumberGenerator, walkInCustomerRepository);
        verify(walkInOrderRepository, never()).save(any());
        verify(productRepository, never()).incrementSoldCount(any(), anyInt());
    }

    @Test
    void quoteRequiresWalkInAccess() {
        User desk = User.builder().id(11L).roles(UserRole.FRONT_DESK).build();
        when(authUserUtil.getCurrentUser()).thenReturn(desk);
        when(frontDeskAccessService.hasPermission(desk, FrontDeskPermission.WALK_IN_ORDER_CREATE)).thenReturn(false);
        assertThatThrownBy(() -> service.quote(new WalkInQuoteRequest(List.of(new WalkInOrderItemRequest(1L, 1)), null, null, null, null)))
                .isInstanceOf(com.example.perfume_budget.exception.ForbiddenException.class);
        verifyNoInteractions(productRepository);
    }

    @Test
    void sameKeySameBodyReplaysWithoutSideEffects() {
        WalkInOrderRequest req = request(2, "300.00");
        when(walkInOrderRepository.findByProcessedByAndIdempotencyKey(admin, "key-12345678"))
                .thenReturn(Optional.of(existingOrder("key-12345678", request(2, "300"))));
        WalkInOrderResponse r = service.placeWalkInOrder(req, "key-12345678");
        assertThat(r.idempotentReplay()).isTrue();
        assertThat(r.orderNumber()).isEqualTo("WI-1");
        verify(userRepository).lockById(10L);
        verifyNoInteractions(inventoryManagementService, bookkeepingService, walkInCustomerRepository);
        verify(walkInOrderRepository, never()).save(any());
    }

    @Test
    void sameKeyDifferentBodyIs409() {
        when(walkInOrderRepository.findByProcessedByAndIdempotencyKey(admin, "key-12345678"))
                .thenReturn(Optional.of(existingOrder("key-12345678", request(2, "300.00"))));
        assertThatThrownBy(() -> service.placeWalkInOrder(request(3, "300.00"), "key-12345678"))
                .isInstanceOfSatisfying(ApiCodeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
        verifyNoInteractions(inventoryManagementService, bookkeepingService);
    }

    @Test
    void newKeyStoresKeyAndFingerprintOnTheOrder() {
        WalkInOrderRequest req = request(1, "200.00");
        when(walkInOrderRepository.findByProcessedByAndIdempotencyKey(admin, "key-12345678")).thenReturn(Optional.empty());
        when(walkInCustomerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walkInOrderNumberGenerator.generateOrderNumber()).thenReturn("WI-2");
        when(walkInOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WalkInOrderResponse r = service.placeWalkInOrder(req, " key-12345678 ");
        assertThat(r.idempotentReplay()).isFalse();
        var captor = org.mockito.ArgumentCaptor.forClass(WalkInOrder.class);
        verify(walkInOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("key-12345678");
        assertThat(captor.getValue().getRequestFingerprint()).isEqualTo(WalkInFingerprint.of(req));
        verify(inventoryManagementService).consumeWalkInInventory(any(), any());
    }

    @Test
    void keylessSaleSkipsTheLockAndTheLookup() {
        when(walkInCustomerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walkInOrderNumberGenerator.generateOrderNumber()).thenReturn("WI-3");
        when(walkInOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.placeWalkInOrder(request(1, "200.00"), null);
        verify(userRepository, never()).lockById(any());
        verify(walkInOrderRepository, never()).findByProcessedByAndIdempotencyKey(any(), any());
    }

    @Test
    void malformedKeyIs400BeforeAnyWork() {
        for (String bad : new String[]{"short", "with space 123", "x".repeat(65), "bad/slash/key1"}) {
            assertThatThrownBy(() -> service.placeWalkInOrder(request(1, "200.00"), bad))
                    .isInstanceOfSatisfying(ApiCodeException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
                    });
        }
        verifyNoInteractions(inventoryManagementService, walkInCustomerRepository);
    }

    @Test
    void fingerprintIgnoresLineOrderScaleAndBlankCustomerFieldsButNotRealChanges() {
        WalkInOrderRequest a = new WalkInOrderRequest(null, new WalkInCustomerRequest("Kofi", " ", null),
                List.of(new WalkInOrderItemRequest(1L, 2), new WalkInOrderItemRequest(2L, 1)), null, null,
                WalkInPaymentMethod.CASH, new BigDecimal("300"), null, null);
        WalkInOrderRequest same = new WalkInOrderRequest(null, new WalkInCustomerRequest(" Kofi ", null, ""),
                List.of(new WalkInOrderItemRequest(2L, 1), new WalkInOrderItemRequest(1L, 2)), null, null,
                WalkInPaymentMethod.CASH, new BigDecimal("300.00"), null, null);
        assertThat(WalkInFingerprint.of(a)).isEqualTo(WalkInFingerprint.of(same)).hasSize(64);

        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(null, a.walkInCustomer(), List.of(new WalkInOrderItemRequest(1L, 3),
                new WalkInOrderItemRequest(2L, 1)), null, null, WalkInPaymentMethod.CASH, new BigDecimal("300"), null, null)))
                .isNotEqualTo(WalkInFingerprint.of(a));
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(null, a.walkInCustomer(), a.items(), DiscountType.FLAT,
                new BigDecimal("1"), WalkInPaymentMethod.CASH, new BigDecimal("300"), null, null))).isNotEqualTo(WalkInFingerprint.of(a));
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(null, a.walkInCustomer(), a.items(), null, null,
                WalkInPaymentMethod.CARD, new BigDecimal("300"), null, null))).isNotEqualTo(WalkInFingerprint.of(a));
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(7L, null, a.items(), null, null,
                WalkInPaymentMethod.CASH, new BigDecimal("300"), null, null))).isNotEqualTo(WalkInFingerprint.of(a));
    }

    private WalkInOrderRequest base() {
        return new WalkInOrderRequest(null, null, List.of(new WalkInOrderItemRequest(1L, 1)), null, null,
                WalkInPaymentMethod.SPLIT, new BigDecimal("100.00"), new BigDecimal("60.00"), new BigDecimal("40.00"));
    }

    @Test
    void eachPaymentAndCustomerFieldChangesTheFingerprint() {
        WalkInOrderRequest b = base();
        String fp = WalkInFingerprint.of(b);
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(b.registeredUserId(), b.walkInCustomer(), b.items(), null, null,
                b.paymentMethod(), new BigDecimal("101.00"), b.splitCashAmount(), b.splitMobileAmount()))).as("amountPaid").isNotEqualTo(fp);
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(b.registeredUserId(), b.walkInCustomer(), b.items(), null, null,
                b.paymentMethod(), b.amountPaid(), new BigDecimal("61.00"), b.splitMobileAmount()))).as("splitCashAmount").isNotEqualTo(fp);
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(b.registeredUserId(), b.walkInCustomer(), b.items(), null, null,
                b.paymentMethod(), b.amountPaid(), b.splitCashAmount(), new BigDecimal("41.00")))).as("splitMobileAmount").isNotEqualTo(fp);
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(5L, b.walkInCustomer(), b.items(), null, null,
                b.paymentMethod(), b.amountPaid(), b.splitCashAmount(), b.splitMobileAmount()))).as("registeredUserId").isNotEqualTo(fp);
        assertThat(WalkInFingerprint.of(new WalkInOrderRequest(null, b.walkInCustomer(), b.items(), null, null,
                WalkInPaymentMethod.CASH, b.amountPaid(), b.splitCashAmount(), b.splitMobileAmount()))).as("paymentMethod").isNotEqualTo(fp);
    }

    @Test
    void customerFieldsCannotCollideAcrossTheSeparator() {
        WalkInOrderRequest x = new WalkInOrderRequest(null, new WalkInCustomerRequest("a", "b/c", ""), base().items(), null, null,
                WalkInPaymentMethod.CASH, new BigDecimal("1"), null, null);
        WalkInOrderRequest y = new WalkInOrderRequest(null, new WalkInCustomerRequest("a/b", "c", ""), base().items(), null, null,
                WalkInPaymentMethod.CASH, new BigDecimal("1"), null, null);
        WalkInOrderRequest z = new WalkInOrderRequest(null, new WalkInCustomerRequest("a", "b", "c"), base().items(), null, null,
                WalkInPaymentMethod.CASH, new BigDecimal("1"), null, null);
        WalkInOrderRequest w = new WalkInOrderRequest(null, new WalkInCustomerRequest("a", "", "b;1:c"), base().items(), null, null,
                WalkInPaymentMethod.CASH, new BigDecimal("1"), null, null);
        assertThat(java.util.Set.of(WalkInFingerprint.of(x), WalkInFingerprint.of(y), WalkInFingerprint.of(z), WalkInFingerprint.of(w))).hasSize(4);
    }
}
