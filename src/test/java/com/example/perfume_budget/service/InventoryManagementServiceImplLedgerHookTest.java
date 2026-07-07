package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.inventory.request.InventoryAdjustmentRequest;
import com.example.perfume_budget.dto.inventory.request.InventoryReceiptRequest;
import com.example.perfume_budget.dto.inventory.response.ProductStockByLocationResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.InventoryAdjustmentDirection;
import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryLayerSourceType;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.StockTransferType;
import com.example.perfume_budget.enums.StorageLocationType;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.InventoryAllocation;
import com.example.perfume_budget.model.InventoryLayer;
import com.example.perfume_budget.model.LocationStock;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.OrderItem;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.StorageLocation;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the location-ledger hooks around the FIFO cost engine:
 * which flows write to the ledger, with what quantity/type — and which flows must not.
 */
@ExtendWith(MockitoExtension.class)
class InventoryManagementServiceImplLedgerHookTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryLayerRepository inventoryLayerRepository;
    @Mock
    private InventoryAllocationRepository inventoryAllocationRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private LocationStockRepository locationStockRepository;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private BookkeepingService bookkeepingService;
    @Mock
    private LocationLedgerSync locationLedgerSync;

    @InjectMocks
    private InventoryManagementServiceImpl inventoryManagementService;

    private Product product;
    private InventoryLayer layer;

    @BeforeEach
    void setUp() {
        product = Product.builder()
                .id(1L)
                .name("Oud Royale")
                .stockQuantity(10)
                .price(new Money(new BigDecimal("20.00"), CurrencyCode.GHS))
                .costPrice(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .build();

        layer = InventoryLayer.builder()
                .id(50L)
                .product(product)
                .sourceType(InventoryLayerSourceType.PURCHASE)
                .receivedQuantity(10)
                .remainingQuantity(10)
                .unitCost(new BigDecimal("10.00"))
                .unitSellingPrice(new BigDecimal("20.00"))
                .currencyCode(CurrencyCode.GHS)
                .receivedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                .build();

        lenient().when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(inventoryLayerRepository.save(any(InventoryLayer.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(inventoryLayerRepository.findByProductIdOrderByReceivedAtAscIdAsc(1L)).thenReturn(List.of(layer));
        lenient().when(inventoryLayerRepository.existsByProductId(1L)).thenReturn(true);
        lenient().when(authUserUtil.getCurrentUser()).thenReturn(null);
    }

    @Test
    void receiveStock_AddsToDefaultReceiving_AsReceipt() {
        inventoryManagementService.receiveStock(new InventoryReceiptRequest(
                1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"), null, "PO-1", "shipment"));

        verify(locationLedgerSync).increaseAtDefaultReceiving(product, 5, StockTransferType.RECEIPT,
                InventoryReferenceType.RECEIPT, "PO-1", "shipment");
    }

    @Test
    void receiveStock_FirstReceipt_RecordedAsOpeningStock() {
        // No existing layers and zero stock → this receipt is the product's opening stock.
        product.setStockQuantity(0);
        when(inventoryLayerRepository.existsByProductId(1L)).thenReturn(false);

        inventoryManagementService.receiveStock(new InventoryReceiptRequest(
                1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"), null, "PO-1", "first shipment"));

        ArgumentCaptor<InventoryLayer> layerCaptor = ArgumentCaptor.forClass(InventoryLayer.class);
        verify(inventoryLayerRepository).save(layerCaptor.capture());
        assertEquals(InventoryLayerSourceType.OPENING_STOCK, layerCaptor.getValue().getSourceType());
    }

    @Test
    void receiveStock_SubsequentReceipt_RecordedAsPurchase() {
        // setUp() already has an existing layer and stock on hand → ordinary purchase.
        inventoryManagementService.receiveStock(new InventoryReceiptRequest(
                1L, 5, new BigDecimal("10.00"), new BigDecimal("20.00"), null, "PO-2", "restock"));

        ArgumentCaptor<InventoryLayer> layerCaptor = ArgumentCaptor.forClass(InventoryLayer.class);
        verify(inventoryLayerRepository).save(layerCaptor.capture());
        assertEquals(InventoryLayerSourceType.PURCHASE, layerCaptor.getValue().getSourceType());
    }

    @Test
    void adjustmentIncrease_AddsToDefaultReceiving_AsAdjustment() {
        inventoryManagementService.adjustInventory(new InventoryAdjustmentRequest(
                1L, InventoryAdjustmentDirection.INCREASE, 3, "stocktake",
                new BigDecimal("10.00"), new BigDecimal("20.00"), "ADJ-1", "found extra"));

        verify(locationLedgerSync).increaseAtDefaultReceiving(product, 3, StockTransferType.ADJUSTMENT,
                InventoryReferenceType.ADJUSTMENT, "ADJ-1", "found extra");
    }

    @Test
    void adjustmentDecrease_DeductsAtDefaultReceiving() {
        when(inventoryLayerRepository.findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(1L, 0))
                .thenReturn(List.of(layer));

        inventoryManagementService.adjustInventory(new InventoryAdjustmentRequest(
                1L, InventoryAdjustmentDirection.DECREASE, 2, "damage", null, null, "ADJ-2", "broken bottles"));

        verify(locationLedgerSync).deductAtDefaultReceiving(product, 2,
                InventoryReferenceType.ADJUSTMENT, "ADJ-2", "broken bottles");
    }

    @Test
    void walkInSale_DeductsFromWalkInSource_WithTotalQuantity() {
        when(inventoryLayerRepository.findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(1L, 0))
                .thenReturn(List.of(layer));
        WalkInOrderItem item = WalkInOrderItem.builder().productId(1L).quantity(2).build();

        inventoryManagementService.consumeWalkInInventory("WIN-1", List.of(item));

        verify(locationLedgerSync).deductForWalkInSale(product, 2, "WIN-1", "Consumed for walk-in order WIN-1");
        assertEquals(new BigDecimal("10.00"), item.getCostPrice());
    }

    @Test
    void reservation_NeverTouchesLedger() {
        when(inventoryLayerRepository.findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(1L, 0))
                .thenReturn(List.of(layer));
        when(inventoryAllocationRepository.save(any(InventoryAllocation.class))).thenAnswer(inv -> inv.getArgument(0));
        OrderItem item = OrderItem.builder().productId(1L).quantity(2)
                .unitPrice(new Money(new BigDecimal("20.00"), CurrencyCode.GHS)).build();

        inventoryManagementService.reserveOrderInventory("ORD-1", List.of(item));

        verifyNoInteractions(locationLedgerSync);
    }

    @Test
    void release_NeverTouchesLedger() {
        InventoryAllocation allocation = InventoryAllocation.builder()
                .id(80L).layer(layer).product(product)
                .referenceType(InventoryReferenceType.ORDER).referenceId("ORD-1").referenceLineKey("product:1")
                .quantity(2).unitCost(new BigDecimal("10.00")).unitSellingPrice(new BigDecimal("20.00"))
                .currencyCode(CurrencyCode.GHS).status(InventoryAllocationStatus.RESERVED)
                .build();
        when(inventoryAllocationRepository.findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
                InventoryReferenceType.ORDER, "ORD-1", InventoryAllocationStatus.RESERVED))
                .thenReturn(List.of(allocation));
        when(inventoryAllocationRepository.save(any(InventoryAllocation.class))).thenAnswer(inv -> inv.getArgument(0));

        inventoryManagementService.releaseOrderInventory("ORD-1");

        verifyNoInteractions(locationLedgerSync);
    }

    @Test
    void finalize_DeductsFromEcommerceSource_SummedPerProduct() {
        InventoryLayer secondLayer = InventoryLayer.builder()
                .id(51L).product(product).sourceType(InventoryLayerSourceType.PURCHASE)
                .receivedQuantity(5).remainingQuantity(5)
                .unitCost(new BigDecimal("12.00")).unitSellingPrice(new BigDecimal("22.00"))
                .currencyCode(CurrencyCode.GHS).receivedAt(LocalDateTime.of(2026, 6, 2, 9, 0))
                .build();
        // Same product reserved across two FIFO layers — ledger must see ONE deduction of 5.
        InventoryAllocation first = InventoryAllocation.builder()
                .id(81L).layer(layer).product(product)
                .referenceType(InventoryReferenceType.ORDER).referenceId("ORD-9").referenceLineKey("product:1")
                .quantity(2).unitCost(new BigDecimal("10.00")).unitSellingPrice(new BigDecimal("20.00"))
                .currencyCode(CurrencyCode.GHS).status(InventoryAllocationStatus.RESERVED)
                .build();
        InventoryAllocation second = InventoryAllocation.builder()
                .id(82L).layer(secondLayer).product(product)
                .referenceType(InventoryReferenceType.ORDER).referenceId("ORD-9").referenceLineKey("product:1")
                .quantity(3).unitCost(new BigDecimal("12.00")).unitSellingPrice(new BigDecimal("22.00"))
                .currencyCode(CurrencyCode.GHS).status(InventoryAllocationStatus.RESERVED)
                .build();
        when(inventoryAllocationRepository.findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
                InventoryReferenceType.ORDER, "ORD-9", InventoryAllocationStatus.RESERVED))
                .thenReturn(List.of(first, second));
        when(inventoryAllocationRepository.save(any(InventoryAllocation.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderItem item = OrderItem.builder().productId(1L).quantity(5)
                .unitPrice(new Money(new BigDecimal("20.00"), CurrencyCode.GHS)).build();
        Order order = Order.builder().orderNumber("ORD-9").items(List.of(item)).build();

        inventoryManagementService.finalizeReservedOrder(order);

        verify(locationLedgerSync).deductForEcommerceSale(product, 5, "ORD-9", "Fulfilled order ORD-9");
        assertEquals(InventoryAllocationStatus.CONSUMED, first.getStatus());
        assertEquals(InventoryAllocationStatus.CONSUMED, second.getStatus());
    }

    @Test
    void insufficientStock_FailsBeforeAnyLedgerWrite() {
        layer.setRemainingQuantity(1);
        when(inventoryLayerRepository.findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(1L, 0))
                .thenReturn(List.of(layer));
        WalkInOrderItem item = WalkInOrderItem.builder().productId(1L).quantity(5).build();

        assertThrows(BadRequestException.class,
                () -> inventoryManagementService.consumeWalkInInventory("WIN-2", List.of(item)));

        verifyNoInteractions(locationLedgerSync);
    }

    @Test
    void stockByLocation_ToleratesOutstandingReservations() {
        StorageLocation shopFloor = StorageLocation.builder()
                .id(1L).name("Shop Floor").type(StorageLocationType.SHOP_FLOOR).active(true).build();
        // Global 10, reserved 2 → ledger should hold 12; floor holds 12 → in sync.
        when(locationStockRepository.findByProductIdWithLocation(1L)).thenReturn(List.of(
                LocationStock.builder().id(5L).product(product).location(shopFloor).quantityOnHand(12).build()));
        when(inventoryAllocationRepository.sumReservedQuantityByProductId(1L)).thenReturn(2);

        ProductStockByLocationResponse response = inventoryManagementService.getProductStockByLocation(1L);

        assertEquals(10, response.globalStockQuantity());
        assertEquals(2, response.outstandingReservedQuantity());
        assertEquals(12, response.locations().getFirst().quantityOnHand());
        assertTrue(response.balancesMatchGlobal());
    }

    @Test
    void stockByLocation_FlagsTrueDrift() {
        StorageLocation shopFloor = StorageLocation.builder()
                .id(1L).name("Shop Floor").type(StorageLocationType.SHOP_FLOOR).active(true).build();
        when(locationStockRepository.findByProductIdWithLocation(anyLong())).thenReturn(List.of(
                LocationStock.builder().id(5L).product(product).location(shopFloor).quantityOnHand(7).build()));
        when(inventoryAllocationRepository.sumReservedQuantityByProductId(1L)).thenReturn(0);

        ProductStockByLocationResponse response = inventoryManagementService.getProductStockByLocation(1L);

        assertFalse(response.balancesMatchGlobal());
    }
}
