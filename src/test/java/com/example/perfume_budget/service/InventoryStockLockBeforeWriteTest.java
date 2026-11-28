package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.inventory.request.InventoryAdjustmentRequest;
import com.example.perfume_budget.dto.inventory.request.InventoryReceiptRequest;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.InventoryAdjustmentDirection;
import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryLayerSourceType;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.InventoryAllocation;
import com.example.perfume_budget.model.InventoryLayer;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.OrderItem;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.WalkInOrderItem;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.repository.InventoryLayerRepository;
import com.example.perfume_budget.repository.InventoryMovementRepository;
import com.example.perfume_budget.repository.LocationStockRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.LocationLedgerSync;
import com.example.perfume_budget.utils.AuthUserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * API-05 rework: every stock writer takes {@link ProductStockLocks#lock} for ALL the products it touches before its
 * first write of a layer, allocation, movement or product row (reviewer mutants: lock removed from adjustment, release,
 * restock, finalisation, walk-in, conversion layer, legacy bootstrap in the admin GETs). The database-level behaviour
 * (the lock really waits, in id order) is covered by StockLockOrderIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class InventoryStockLockBeforeWriteTest {

    @Mock private ProductRepository productRepository;
    @Mock private InventoryLayerRepository inventoryLayerRepository;
    @Mock private InventoryAllocationRepository inventoryAllocationRepository;
    @Mock private InventoryMovementRepository inventoryMovementRepository;
    @Mock private LocationStockRepository locationStockRepository;
    @Mock private AuthUserUtil authUserUtil;
    @Mock private BookkeepingService bookkeepingService;
    @Mock private LocationLedgerSync locationLedgerSync;
    @Mock private ProductStockLocks stockLocks;
    @Mock private com.example.perfume_budget.service.interfaces.FrontDeskAccessService frontDeskAccessService;

    @Mock
    private com.example.perfume_budget.entitlement.EntitlementService entitlementService; // PLAN-02 gates (no-op mock)

    @InjectMocks private InventoryManagementServiceImpl service;

    /** "lock [ids]" or "write <what>", in call order. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private Product p1;
    private Product p2;
    private InventoryLayer l1;
    private InventoryLayer l2;

    @BeforeEach
    void setUp() {
        p1 = product(1L);
        p2 = product(2L);
        l1 = layer(50L, p1);
        l2 = layer(51L, p2);
        lenient().when(productRepository.findById(1L)).thenReturn(Optional.of(p1));
        lenient().when(productRepository.findById(2L)).thenReturn(Optional.of(p2));
        lenient().when(inventoryLayerRepository.existsByProductId(anyLong())).thenReturn(true);
        lenient().when(inventoryLayerRepository.findByProductIdOrderByReceivedAtAscIdAsc(1L)).thenReturn(List.of(l1));
        lenient().when(inventoryLayerRepository.findByProductIdOrderByReceivedAtAscIdAsc(2L)).thenReturn(List.of(l2));
        lenient().when(inventoryLayerRepository.findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(1L, 0))
                .thenReturn(List.of(l1));
        lenient().when(inventoryLayerRepository.findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(2L, 0))
                .thenReturn(List.of(l2));
        lenient().when(stockLocks.fresh(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().doAnswer(inv -> {
            List<Long> ids = new ArrayList<>(inv.<Collection<Long>>getArgument(0));
            ids.sort(null);
            calls.add("lock " + ids);
            return null;
        }).when(stockLocks).lock(any());
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> write("product", inv.getArgument(0)));
        lenient().when(inventoryLayerRepository.save(any(InventoryLayer.class))).thenAnswer(inv -> write("layer", inv.getArgument(0)));
        lenient().when(inventoryAllocationRepository.save(any(InventoryAllocation.class))).thenAnswer(inv -> write("allocation", inv.getArgument(0)));
        lenient().when(inventoryMovementRepository.save(any())).thenAnswer(inv -> write("movement", inv.getArgument(0)));
        lenient().doAnswer(inv -> write("soldCount", null)).when(productRepository).incrementSoldCount(anyLong(), anyInt());
    }

    private <T> T write(String what, T entity) {
        calls.add("write " + what);
        return entity;
    }

    private static Product product(long id) {
        return Product.builder().id(id).name("P" + id).stockQuantity(10)
                .price(new Money(new BigDecimal("20.00"), CurrencyCode.GHS))
                .costPrice(new Money(new BigDecimal("10.00"), CurrencyCode.GHS)).build();
    }

    private static InventoryLayer layer(long id, Product p) {
        return InventoryLayer.builder().id(id).product(p).sourceType(InventoryLayerSourceType.PURCHASE)
                .receivedQuantity(10).remainingQuantity(10).unitCost(new BigDecimal("10.00"))
                .unitSellingPrice(new BigDecimal("20.00")).currencyCode(CurrencyCode.GHS)
                .receivedAt(LocalDateTime.of(2026, 6, 1, 9, 0)).build();
    }

    private static InventoryAllocation allocation(long id, InventoryLayer layer, String order, InventoryAllocationStatus st) {
        return InventoryAllocation.builder().id(id).layer(layer).product(layer.getProduct())
                .referenceType(InventoryReferenceType.ORDER).referenceId(order)
                .referenceLineKey("product:" + layer.getProduct().getId()).quantity(2)
                .unitCost(new BigDecimal("10.00")).unitSellingPrice(new BigDecimal("20.00"))
                .currencyCode(CurrencyCode.GHS).status(st).build();
    }

    private static OrderItem item(long productId) {
        return OrderItem.builder().productId(productId).quantity(2)
                .unitPrice(new Money(new BigDecimal("20.00"), CurrencyCode.GHS)).build();
    }

    /** The first call is the lock of exactly these products, and something was written after it. */
    private void assertLockedFirst(String ids) {
        assertThat(calls).as(calls.toString()).isNotEmpty();
        assertThat(calls.getFirst()).as(calls.toString()).isEqualTo("lock " + ids);
        assertThat(calls).as(calls.toString()).anyMatch(c -> c.startsWith("write"));
    }

    @Test
    void receipt() {
        service.receiveStock(new InventoryReceiptRequest(1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"),
                null, "PO-1", "n"));
        assertLockedFirst("[1]");
    }

    @Test
    void adjustmentIncrease() {
        service.adjustInventory(new InventoryAdjustmentRequest(1L, InventoryAdjustmentDirection.INCREASE, 3, "count",
                new BigDecimal("10.00"), new BigDecimal("20.00"), "ADJ-1", "n"));
        assertLockedFirst("[1]");
    }

    @Test
    void adjustmentDecrease() {
        service.adjustInventory(new InventoryAdjustmentRequest(1L, InventoryAdjustmentDirection.DECREASE, 2, "damage",
                null, null, "ADJ-2", "n"));
        assertLockedFirst("[1]");
    }

    @Test
    void reservationOfAMultiProductOrderLocksAllItsProducts() {
        service.reserveOrderInventory("ORD-1", List.of(item(2L), item(1L)));
        assertLockedFirst("[1, 2]");
        assertThat(calls.stream().filter(c -> c.startsWith("lock")).count()).isEqualTo(1);
    }

    @Test
    void release() {
        lenient().when(inventoryAllocationRepository.findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
                InventoryReferenceType.ORDER, "ORD-1", InventoryAllocationStatus.RESERVED))
                .thenReturn(List.of(allocation(80L, l2, "ORD-1", InventoryAllocationStatus.RESERVED),
                        allocation(81L, l1, "ORD-1", InventoryAllocationStatus.RESERVED)));
        service.releaseOrderInventory("ORD-1");
        assertLockedFirst("[1, 2]");
    }

    @Test
    void finalisation() {
        lenient().when(inventoryAllocationRepository.findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
                InventoryReferenceType.ORDER, "ORD-2", InventoryAllocationStatus.RESERVED))
                .thenReturn(List.of(allocation(82L, l2, "ORD-2", InventoryAllocationStatus.RESERVED),
                        allocation(83L, l1, "ORD-2", InventoryAllocationStatus.RESERVED)));
        service.finalizeReservedOrder(Order.builder().orderNumber("ORD-2").items(List.of(item(2L), item(1L))).build());
        assertLockedFirst("[1, 2]");
    }

    @Test
    void merchantRestockLocksBeforeLayersAndSoldCounts() {
        lenient().when(inventoryAllocationRepository.findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
                InventoryReferenceType.ORDER, "ORD-3", InventoryAllocationStatus.CONSUMED))
                .thenReturn(List.of(allocation(84L, l2, "ORD-3", InventoryAllocationStatus.CONSUMED),
                        allocation(85L, l1, "ORD-3", InventoryAllocationStatus.CONSUMED)));
        service.restockConsumedOrderInventory(Order.builder().orderNumber("ORD-3").items(List.of(item(2L), item(1L))).build());
        assertLockedFirst("[1, 2]");
        assertThat(calls).contains("write soldCount");
    }

    @Test
    void walkIn() {
        service.consumeWalkInInventory("WIN-1", List.of(
                WalkInOrderItem.builder().productId(2L).quantity(1).build(),
                WalkInOrderItem.builder().productId(1L).quantity(1).build()));
        assertLockedFirst("[1, 2]");
    }

    @Test
    void consumeInventoryForAConversion() {
        service.consumeInventory(p1, 2, InventoryReferenceType.CONVERSION, "CONV-1", "product:1", "n", false);
        assertLockedFirst("[1]");
    }

    @Test
    void conversionLayer() {
        service.createConversionLayer(p2, 10, new BigDecimal("1.00"), new BigDecimal("2.00"),
                InventoryLayerSourceType.CONVERSION_IN, "CONV-1", "n", LocalDateTime.now());
        assertLockedFirst("[2]");
    }

    @Test
    void adminSummaryAndHistoryGetsBootstrapALegacyBalanceOnlyUnderTheLock() {
        lenient().when(inventoryLayerRepository.existsByProductId(1L)).thenReturn(false); // legacy: stock 10, no layer
        service.getProductInventorySummary(1L);
        assertLockedFirst("[1]");
        assertThat(calls).contains("write layer");

        calls.clear();
        service.getProductInventoryHistory(1L);
        assertLockedFirst("[1]");
    }

    @Test
    void adminGetsOfAProductThatAlreadyHasLayersTakeNoLockAndWriteNothing() {
        service.getProductInventorySummary(1L);
        service.getProductInventoryHistory(1L);
        assertThat(calls).as(calls.toString()).noneMatch(c -> c.startsWith("lock"));
        assertThat(calls).as(calls.toString()).noneMatch(c -> c.startsWith("write layer") || c.startsWith("write movement"));
    }

    // ------------------------------------------------------------------ INV-01: legacy balance under every layer writer

    /** The source types of the layers saved, in save order. */
    private List<InventoryLayerSourceType> savedLayerTypes() {
        org.mockito.ArgumentCaptor<InventoryLayer> c = org.mockito.ArgumentCaptor.forClass(InventoryLayer.class);
        org.mockito.Mockito.verify(inventoryLayerRepository, org.mockito.Mockito.atLeastOnce()).save(c.capture());
        return c.getAllValues().stream().map(InventoryLayer::getSourceType).toList();
    }

    private InventoryLayer savedLegacyLayer() {
        org.mockito.ArgumentCaptor<InventoryLayer> c = org.mockito.ArgumentCaptor.forClass(InventoryLayer.class);
        org.mockito.Mockito.verify(inventoryLayerRepository, org.mockito.Mockito.atLeastOnce()).save(c.capture());
        return c.getAllValues().stream().filter(l -> l.getSourceType() == InventoryLayerSourceType.LEGACY_BALANCE)
                .findFirst().orElseThrow();
    }

    private void legacy(long productId) {
        lenient().when(inventoryLayerRepository.existsByProductId(productId)).thenReturn(false); // stock 10, no layer
    }

    @Test
    void receiptOnALegacyProductLocksThenBootstrapsTheLegacyLayerBeforeItsOwnLayer() {
        legacy(1L);
        service.receiveStock(new InventoryReceiptRequest(1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"),
                null, "PO-1", "n"));
        assertLockedFirst("[1]");
        assertThat(savedLayerTypes()).containsExactly(InventoryLayerSourceType.LEGACY_BALANCE, InventoryLayerSourceType.PURCHASE);
        assertThat(savedLegacyLayer().getReceivedQuantity()).isEqualTo(10);
        assertThat(savedLegacyLayer().getRemainingQuantity()).isEqualTo(10);
    }

    @Test
    void adjustmentIncreaseOnALegacyProductBootstrapsTheLegacyLayerFirst() {
        legacy(1L);
        service.adjustInventory(new InventoryAdjustmentRequest(1L, InventoryAdjustmentDirection.INCREASE, 3, "count",
                new BigDecimal("10.00"), new BigDecimal("20.00"), "ADJ-1", "n"));
        assertLockedFirst("[1]");
        assertThat(savedLayerTypes()).containsExactly(InventoryLayerSourceType.LEGACY_BALANCE, InventoryLayerSourceType.ADJUSTMENT_IN);
    }

    @Test
    void conversionLayerOnALegacyTargetBootstrapsTheLegacyLayerFirst() {
        legacy(2L);
        service.createConversionLayer(p2, 20, new BigDecimal("5.00"), new BigDecimal("12.00"),
                InventoryLayerSourceType.CONVERSION_IN, "CNV-1", "n", LocalDateTime.now());
        assertLockedFirst("[2]");
        assertThat(savedLayerTypes()).containsExactly(InventoryLayerSourceType.LEGACY_BALANCE, InventoryLayerSourceType.CONVERSION_IN);
    }

    /** INV-01b L3: the opt-in repair writes a layer, a movement and the product row, so it takes the lock first. */
    @Test
    void legacyBalanceRepairLocksTheProductBeforeItWritesAnything() {
        lenient().when(inventoryLayerRepository.findLegacyBalanceCandidate(1L)).thenReturn(Optional.of(
                new com.example.perfume_budget.projection.LegacyBalanceCandidate() {
                    public Long getProductId() { return 1L; }
                    public String getSku() { return "P1"; }
                    public String getName() { return "P1"; }
                    public Integer getStockQuantity() { return 10; }
                    public Integer getReservedQuantity() { return 0; }
                    public Integer getLocationOnHand() { return 15; }
                }));
        var result = service.repairLegacyBalances(List.of(1L));
        assertThat(result.restoredUnits()).isEqualTo(5);
        assertLockedFirst("[1]");
        assertThat(savedLegacyLayer().getReceivedQuantity()).isEqualTo(5);
    }

    @Test
    void aZeroLegacyBalanceGetsNoLegacyLayer() {
        legacy(1L);
        p1.setStockQuantity(0);
        service.receiveStock(new InventoryReceiptRequest(1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"),
                null, "PO-1", "n"));
        assertThat(savedLayerTypes()).containsExactly(InventoryLayerSourceType.OPENING_STOCK);
    }

    @Test
    void theLegacyLayerIsReceivedNoLaterThanABackdatedReceipt() {
        legacy(1L);
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 8, 0);
        service.receiveStock(new InventoryReceiptRequest(1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"),
                past, "PO-1", "n"));
        assertThat(savedLegacyLayer().getReceivedAt()).isEqualTo(past);
    }

    @Test
    void theLegacyLayerIsNeverDatedInTheFuture() {
        legacy(1L);
        LocalDateTime before = LocalDateTime.now();
        service.receiveStock(new InventoryReceiptRequest(1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"),
                before.plusDays(3), "PO-1", "n"));
        assertThat(savedLegacyLayer().getReceivedAt()).isBetween(before, LocalDateTime.now());
    }

    @Test
    void summaryIsGatedByProductViewStockSummaryBeforeAnyLockOrWrite() {
        org.mockito.Mockito.when(frontDeskAccessService.requireAccess(
                com.example.perfume_budget.enums.FrontDeskPermission.PRODUCT_VIEW_STOCK_SUMMARY))
                .thenThrow(new com.example.perfume_budget.exception.ForbiddenException("no"));
        org.junit.jupiter.api.Assertions.assertThrows(com.example.perfume_budget.exception.ForbiddenException.class,
                () -> service.getProductInventorySummary(1L));
        assertThat(calls).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(productRepository);
        org.mockito.Mockito.verify(frontDeskAccessService)
                .requireAccess(com.example.perfume_budget.enums.FrontDeskPermission.PRODUCT_VIEW_STOCK_SUMMARY);
    }
}
