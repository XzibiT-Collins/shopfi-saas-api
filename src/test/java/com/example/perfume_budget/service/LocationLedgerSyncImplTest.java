package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.StockTransferType;
import com.example.perfume_budget.enums.StorageLocationType;
import com.example.perfume_budget.events.ShopFloorStockEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.LocationStock;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.StockTransfer;
import com.example.perfume_budget.model.StorageLocation;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.LocationStockRepository;
import com.example.perfume_budget.repository.StockTransferRepository;
import com.example.perfume_budget.repository.StorageLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LocationLedgerSyncImplTest {

    @Mock
    private LocationStockRepository locationStockRepository;
    @Mock
    private StockTransferRepository stockTransferRepository;
    @Mock
    private StorageLocationRepository storageLocationRepository;
    @Mock
    private com.example.perfume_budget.utils.AuthUserUtil authUserUtil;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private LocationLedgerSyncImpl locationLedgerSync;

    private Product product;
    private StorageLocation shopFloor;
    private StorageLocation storeRoom;
    private User staff;

    @BeforeEach
    void setUp() {
        product = Product.builder().id(7L).name("Oud Royale").build();
        shopFloor = StorageLocation.builder().id(1L).name("Shop Floor").type(StorageLocationType.SHOP_FLOOR).active(true).build();
        storeRoom = StorageLocation.builder().id(2L).name("Store Room 1").type(StorageLocationType.STORE_ROOM).active(true).build();
        staff = User.builder().id(5L).fullName("Jane Doe").build();

        lenient().when(stockTransferRepository.save(any(StockTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private LocationStock balance(StorageLocation location, int quantity) {
        return LocationStock.builder().id(100L + location.getId()).product(product).location(location).quantityOnHand(quantity).build();
    }

    @Test
    void applyDelta_IncreasesBalance_LogsReceiptRow_NoEventOnIncrement() {
        LocationStock floorStock = balance(shopFloor, 3);
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(floorStock));

        locationLedgerSync.applyDelta(product, shopFloor, 5, StockTransferType.RECEIPT, null, null, "received", staff);

        assertEquals(8, floorStock.getQuantityOnHand());
        verify(locationStockRepository).save(floorStock);

        ArgumentCaptor<StockTransfer> captor = ArgumentCaptor.forClass(StockTransfer.class);
        verify(stockTransferRepository).save(captor.capture());
        StockTransfer logged = captor.getValue();
        assertNull(logged.getFromLocation());
        assertEquals(shopFloor, logged.getToLocation());
        assertEquals(5, logged.getQuantity());
        assertEquals(StockTransferType.RECEIPT, logged.getTransferType());
        assertEquals(staff, logged.getMovedBy());
        assertEquals("received", logged.getNote());
        assertEquals(8, logged.getBalanceAfter());

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void applyDelta_AllowsNegativeBalance_AndPublishesFloorEvent() {
        LocationStock floorStock = balance(shopFloor, 1);
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(floorStock));

        locationLedgerSync.applyDelta(product, shopFloor, -3, StockTransferType.SALE_DEDUCTION, null, null, "walk-in", staff);

        assertEquals(-2, floorStock.getQuantityOnHand());

        ArgumentCaptor<StockTransfer> transferCaptor = ArgumentCaptor.forClass(StockTransfer.class);
        verify(stockTransferRepository).save(transferCaptor.capture());
        assertEquals(shopFloor, transferCaptor.getValue().getFromLocation());
        assertNull(transferCaptor.getValue().getToLocation());
        assertEquals(3, transferCaptor.getValue().getQuantity());

        ArgumentCaptor<ShopFloorStockEvent> eventCaptor = ArgumentCaptor.forClass(ShopFloorStockEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(7L, eventCaptor.getValue().productId());
        assertEquals("Oud Royale", eventCaptor.getValue().productName());
    }

    @Test
    void applyDelta_StoreRoomDecrement_NoEvent() {
        when(locationStockRepository.findForUpdate(7L, 2L)).thenReturn(Optional.of(balance(storeRoom, 10)));

        locationLedgerSync.applyDelta(product, storeRoom, -4, StockTransferType.ADJUSTMENT, null, null, "shrinkage", staff);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void applyDelta_CreatesBalanceRow_WhenMissing() {
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.empty());
        when(locationStockRepository.save(any(LocationStock.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        locationLedgerSync.applyDelta(product, shopFloor, 5, StockTransferType.RECEIPT, null, null, "first stock", staff);

        ArgumentCaptor<LocationStock> captor = ArgumentCaptor.forClass(LocationStock.class);
        verify(locationStockRepository, times(2)).save(captor.capture());
        LocationStock finalState = captor.getAllValues().get(1);
        assertEquals(product, finalState.getProduct());
        assertEquals(shopFloor, finalState.getLocation());
        assertEquals(5, finalState.getQuantityOnHand());
    }

    @Test
    void applyDelta_ZeroDelta_DoesNothing() {
        locationLedgerSync.applyDelta(product, shopFloor, 0, StockTransferType.RECEIPT, null, null, "noop", staff);

        verifyNoInteractions(locationStockRepository, stockTransferRepository, eventPublisher);
    }

    @Test
    void increaseAtDefaultReceiving_SkipsSilently_WhenNoLocationConfigured() {
        when(storageLocationRepository.findByIsDefaultReceivingTrue()).thenReturn(Optional.empty());

        locationLedgerSync.increaseAtDefaultReceiving(product, 5, StockTransferType.RECEIPT, null, null, "received");

        verifyNoInteractions(locationStockRepository, stockTransferRepository, eventPublisher);
    }

    @Test
    void deductForWalkInSale_DeductsFromWalkInSource_WithCurrentUser() {
        when(storageLocationRepository.findByIsWalkInSaleSourceTrue()).thenReturn(Optional.of(shopFloor));
        when(authUserUtil.getCurrentUser()).thenReturn(staff);
        LocationStock floorStock = balance(shopFloor, 10);
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(floorStock));

        locationLedgerSync.deductForWalkInSale(product, 2, "WIN-1", "walk-in WIN-1");

        assertEquals(8, floorStock.getQuantityOnHand());
        ArgumentCaptor<StockTransfer> captor = ArgumentCaptor.forClass(StockTransfer.class);
        verify(stockTransferRepository).save(captor.capture());
        assertEquals(StockTransferType.SALE_DEDUCTION, captor.getValue().getTransferType());
        assertEquals(staff, captor.getValue().getMovedBy());
        assertEquals(8, captor.getValue().getBalanceAfter());
        assertEquals(InventoryReferenceType.WALK_IN_ORDER, captor.getValue().getReferenceType());
        assertEquals("WIN-1", captor.getValue().getReferenceId());
    }

    @Test
    void deductForEcommerceSale_UsesFulfilmentSource() {
        when(storageLocationRepository.findByIsEcommerceFulfilmentSourceTrue()).thenReturn(Optional.of(storeRoom));
        when(authUserUtil.getCurrentUser()).thenReturn(null);
        LocationStock roomStock = balance(storeRoom, 6);
        when(locationStockRepository.findForUpdate(7L, 2L)).thenReturn(Optional.of(roomStock));

        locationLedgerSync.deductForEcommerceSale(product, 6, "ORD-9", "order ORD-9");

        assertEquals(0, roomStock.getQuantityOnHand());
        ArgumentCaptor<StockTransfer> captor = ArgumentCaptor.forClass(StockTransfer.class);
        verify(stockTransferRepository).save(captor.capture());
        assertEquals(StockTransferType.SALE_DEDUCTION, captor.getValue().getTransferType());
        assertNull(captor.getValue().getMovedBy());
        assertEquals(InventoryReferenceType.ORDER, captor.getValue().getReferenceType());
        assertEquals("ORD-9", captor.getValue().getReferenceId());
    }

    @Test
    void deductAtDefaultReceiving_LogsAdjustment() {
        when(storageLocationRepository.findByIsDefaultReceivingTrue()).thenReturn(Optional.of(shopFloor));
        when(authUserUtil.getCurrentUser()).thenReturn(staff);
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(balance(shopFloor, 5)));

        locationLedgerSync.deductAtDefaultReceiving(product, 2, InventoryReferenceType.ADJUSTMENT, "ADJ-1", "damaged");

        ArgumentCaptor<StockTransfer> captor = ArgumentCaptor.forClass(StockTransfer.class);
        verify(stockTransferRepository).save(captor.capture());
        assertEquals(StockTransferType.ADJUSTMENT, captor.getValue().getTransferType());
        assertEquals(2, captor.getValue().getQuantity());
    }

    @Test
    void transfer_MovesQuantity_LogsSingleTransferRow() {
        LocationStock fromStock = balance(storeRoom, 10);
        LocationStock toStock = balance(shopFloor, 0);
        when(locationStockRepository.findForUpdate(7L, 2L)).thenReturn(Optional.of(fromStock));
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(toStock));

        StockTransfer result = locationLedgerSync.transfer(product, storeRoom, shopFloor, 4, "restock floor", staff);

        assertEquals(6, fromStock.getQuantityOnHand());
        assertEquals(4, toStock.getQuantityOnHand());
        verify(stockTransferRepository, times(1)).save(any(StockTransfer.class));
        assertEquals(StockTransferType.TRANSFER, result.getTransferType());
        assertEquals(storeRoom, result.getFromLocation());
        assertEquals(shopFloor, result.getToLocation());
        assertEquals(4, result.getQuantity());
        assertEquals(staff, result.getMovedBy());
        // Store room is the source — no floor decrement event.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void transfer_LocksRowsOrderedByLocationId_RegardlessOfDirection() {
        // from has the HIGHER id — lock must still acquire lower id first.
        LocationStock fromStock = balance(storeRoom, 10);
        LocationStock toStock = balance(shopFloor, 0);
        when(locationStockRepository.findForUpdate(7L, 2L)).thenReturn(Optional.of(fromStock));
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(toStock));

        locationLedgerSync.transfer(product, storeRoom, shopFloor, 1, null, staff);

        InOrder order = inOrder(locationStockRepository);
        order.verify(locationStockRepository).findForUpdate(7L, 1L);
        order.verify(locationStockRepository).findForUpdate(7L, 2L);
    }

    @Test
    void transfer_RejectsInsufficientSourceStock() {
        LocationStock fromStock = balance(storeRoom, 3);
        LocationStock toStock = balance(shopFloor, 0);
        when(locationStockRepository.findForUpdate(7L, 2L)).thenReturn(Optional.of(fromStock));
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(toStock));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> locationLedgerSync.transfer(product, storeRoom, shopFloor, 5, null, staff));

        assertEquals("Insufficient stock at Store Room 1: 3 on hand, 5 requested.", ex.getMessage());
        assertEquals(3, fromStock.getQuantityOnHand());
        assertEquals(0, toStock.getQuantityOnHand());
        verify(stockTransferRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void transfer_PublishesFloorEvent_WhenFloorIsSource() {
        LocationStock fromStock = balance(shopFloor, 5);
        LocationStock toStock = balance(storeRoom, 0);
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(fromStock));
        when(locationStockRepository.findForUpdate(7L, 2L)).thenReturn(Optional.of(toStock));

        locationLedgerSync.transfer(product, shopFloor, storeRoom, 5, null, staff);

        verify(eventPublisher).publishEvent(any(ShopFloorStockEvent.class));
    }

    @Test
    void transfer_AllowsExactSourceBalance() {
        LocationStock fromStock = balance(storeRoom, 5);
        LocationStock toStock = balance(shopFloor, 1);
        when(locationStockRepository.findForUpdate(7L, 2L)).thenReturn(Optional.of(fromStock));
        when(locationStockRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(toStock));

        locationLedgerSync.transfer(product, storeRoom, shopFloor, 5, null, staff);

        assertEquals(0, fromStock.getQuantityOnHand());
        assertEquals(6, toStock.getQuantityOnHand());
    }
}
