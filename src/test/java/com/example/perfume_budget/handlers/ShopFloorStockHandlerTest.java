package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.enums.StorageLocationType;
import com.example.perfume_budget.events.ShopFloorStockEvent;
import com.example.perfume_budget.model.LocationStock;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.StorageLocation;
import com.example.perfume_budget.repository.LocationStockRepository;
import com.example.perfume_budget.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopFloorStockHandlerTest {

    @Mock
    private LocationStockRepository locationStockRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ShopFloorStockHandler handler;

    private final ShopFloorStockEvent event = new ShopFloorStockEvent(7L, "Oud Royale");

    private Product product;
    private StorageLocation shopFloor;

    @BeforeEach
    void setUp() {
        product = Product.builder().id(7L).name("Oud Royale").lowStockThreshold(5).build();
        shopFloor = StorageLocation.builder()
                .id(1L).name("Shop Floor").type(StorageLocationType.SHOP_FLOOR).active(true)
                .build();
    }

    private LocationStock floorStock(int quantity) {
        return LocationStock.builder().id(10L).product(product).location(shopFloor).quantityOnHand(quantity).build();
    }

    @Test
    void notifies_WhenFloorAtOrBelowThreshold_AndStoreRoomHasStock() {
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(0)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(locationStockRepository.sumQuantityByProductAndLocationType(7L, StorageLocationType.STORE_ROOM))
                .thenReturn(20);

        handler.handleShopFloorStockChange(event);

        verify(notificationService).notifyStaffOfShopFloorShortage(7L, "Oud Royale", 0, 20);
    }

    @Test
    void notifies_OnNegativeFloorBalance() {
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(-2)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(locationStockRepository.sumQuantityByProductAndLocationType(7L, StorageLocationType.STORE_ROOM))
                .thenReturn(3);

        handler.handleShopFloorStockChange(event);

        verify(notificationService).notifyStaffOfShopFloorShortage(7L, "Oud Royale", -2, 3);
    }

    @Test
    void locationThreshold_OverridesProductThreshold() {
        shopFloor.setLowStockThreshold(0);
        // Floor at 1: above location threshold (0) even though below product threshold (5).
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(1)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(locationStockRepository.sumQuantityByProductAndLocationType(7L, StorageLocationType.STORE_ROOM))
                .thenReturn(20);

        handler.handleShopFloorStockChange(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void fallsBackToProductThreshold_WhenLocationThresholdNull() {
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(5)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(locationStockRepository.sumQuantityByProductAndLocationType(7L, StorageLocationType.STORE_ROOM))
                .thenReturn(2);

        handler.handleShopFloorStockChange(event);

        // 5 <= product threshold 5 → alert.
        verify(notificationService).notifyStaffOfShopFloorShortage(7L, "Oud Royale", 5, 2);
    }

    @Test
    void defaultsToZeroThreshold_WhenNoThresholdConfiguredAnywhere() {
        product.setLowStockThreshold(null);
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(1)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(locationStockRepository.sumQuantityByProductAndLocationType(7L, StorageLocationType.STORE_ROOM))
                .thenReturn(20);

        handler.handleShopFloorStockChange(event);

        // 1 > 0 → no alert.
        verifyNoInteractions(notificationService);
    }

    @Test
    void noAlert_WhenStoreRoomsEmpty() {
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(0)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(locationStockRepository.sumQuantityByProductAndLocationType(7L, StorageLocationType.STORE_ROOM))
                .thenReturn(0);

        handler.handleShopFloorStockChange(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void noAlert_WhenFloorAboveThreshold() {
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(6)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(locationStockRepository.sumQuantityByProductAndLocationType(7L, StorageLocationType.STORE_ROOM))
                .thenReturn(20);

        handler.handleShopFloorStockChange(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void skips_WhenNoFloorBalanceRows() {
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of());

        handler.handleShopFloorStockChange(event);

        verifyNoInteractions(productRepository, notificationService);
    }

    @Test
    void skips_WhenProductGone() {
        when(locationStockRepository.findByProductIdAndLocationType(7L, StorageLocationType.SHOP_FLOOR))
                .thenReturn(List.of(floorStock(0)));
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        handler.handleShopFloorStockChange(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void swallowsExceptions_NeverPropagates() {
        when(locationStockRepository.findByProductIdAndLocationType(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> handler.handleShopFloorStockChange(event));
        verifyNoInteractions(notificationService);
    }
}
