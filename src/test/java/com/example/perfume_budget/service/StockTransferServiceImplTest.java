package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.inventory.request.StockTransferRequest;
import com.example.perfume_budget.dto.inventory.response.StockTransferResponse;
import com.example.perfume_budget.enums.StockTransferType;
import com.example.perfume_budget.enums.StorageLocationType;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.StockTransfer;
import com.example.perfume_budget.model.StorageLocation;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.StockTransferRepository;
import com.example.perfume_budget.repository.StorageLocationRepository;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.LocationLedgerSync;
import com.example.perfume_budget.utils.AuthUserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockTransferServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private StorageLocationRepository storageLocationRepository;
    @Mock
    private StockTransferRepository stockTransferRepository;
    @Mock
    private LocationLedgerSync locationLedgerSync;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private FrontDeskAccessService frontDeskAccessService;

    @InjectMocks
    private StockTransferServiceImpl stockTransferService;

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
    }

    @Test
    void transfer_RejectsSameSourceAndDestination_BeforeAnyLookup() {
        assertThrows(BadRequestException.class, () -> stockTransferService.transfer(
                new StockTransferRequest(7L, 2L, 2L, 5, null)));

        verifyNoInteractions(productRepository, storageLocationRepository, locationLedgerSync);
    }

    @Test
    void transfer_ThrowsWhenProductUnknown() {
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> stockTransferService.transfer(
                new StockTransferRequest(7L, 2L, 1L, 5, null)));
        verifyNoInteractions(locationLedgerSync);
    }

    @Test
    void transfer_ThrowsWhenLocationUnknown() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> stockTransferService.transfer(
                new StockTransferRequest(7L, 2L, 1L, 5, null)));
        verifyNoInteractions(locationLedgerSync);
    }

    @Test
    void transfer_RejectsInactiveLocation() {
        storeRoom.setActive(false);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> stockTransferService.transfer(
                new StockTransferRequest(7L, 2L, 1L, 5, null)));

        assertEquals("Storage location 'Store Room 1' is inactive.", ex.getMessage());
        verifyNoInteractions(locationLedgerSync);
    }

    @Test
    void transfer_DelegatesToLedger_WithCurrentUser_AndMapsResponse() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));
        when(storageLocationRepository.findById(1L)).thenReturn(Optional.of(shopFloor));
        when(authUserUtil.getCurrentUser()).thenReturn(staff);
        StockTransfer saved = StockTransfer.builder()
                .id(42L).product(product).fromLocation(storeRoom).toLocation(shopFloor)
                .quantity(5).movedBy(staff).transferType(StockTransferType.TRANSFER)
                .note("restock").createdAt(LocalDateTime.of(2026, 6, 12, 14, 0))
                .build();
        when(locationLedgerSync.transfer(product, storeRoom, shopFloor, 5, "restock", staff)).thenReturn(saved);

        StockTransferResponse response = stockTransferService.transfer(
                new StockTransferRequest(7L, 2L, 1L, 5, "restock"));

        verify(locationLedgerSync).transfer(product, storeRoom, shopFloor, 5, "restock", staff);
        assertEquals(42L, response.id());
        assertEquals("Oud Royale", response.productName());
        assertEquals("Store Room 1", response.fromLocationName());
        assertEquals("Shop Floor", response.toLocationName());
        assertEquals("TRANSFER", response.transferType());
        assertEquals("Jane Doe", response.movedByName());
    }

    @Test
    void history_MapsPage_SystemActorAndNullableLocations() {
        StockTransfer systemRow = StockTransfer.builder()
                .id(43L).product(product).fromLocation(shopFloor).toLocation(null)
                .quantity(2).movedBy(null).transferType(StockTransferType.SALE_DEDUCTION)
                .build();
        when(stockTransferRepository.findHistory(7L, null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(systemRow)));

        Page<StockTransferResponse> page = stockTransferService.history(7L, null, 0, 20);

        StockTransferResponse row = page.getContent().getFirst();
        assertEquals("System", row.movedByName());
        assertNull(row.toLocationId());
        assertNull(row.toLocationName());
        assertEquals("Shop Floor", row.fromLocationName());
        assertEquals("SALE_DEDUCTION", row.transferType());
    }

    @Test
    void history_PassesFiltersThrough() {
        when(stockTransferRepository.findHistory(null, 2L, PageRequest.of(1, 10)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<StockTransferResponse> page = stockTransferService.history(null, 2L, 1, 10);

        assertEquals(0, page.getTotalElements());
        verify(stockTransferRepository).findHistory(null, 2L, PageRequest.of(1, 10));
    }
}
