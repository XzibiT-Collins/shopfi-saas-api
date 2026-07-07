package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.inventory.request.LocationDefaultsRequest;
import com.example.perfume_budget.dto.inventory.request.StorageLocationRequest;
import com.example.perfume_budget.dto.inventory.response.StorageLocationResponse;
import com.example.perfume_budget.enums.StorageLocationType;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.StorageLocation;
import com.example.perfume_budget.repository.StorageLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageLocationServiceImplTest {

    @Mock
    private StorageLocationRepository storageLocationRepository;

    @InjectMocks
    private StorageLocationServiceImpl storageLocationService;

    private StorageLocation shopFloor;
    private StorageLocation storeRoom;

    @BeforeEach
    void setUp() {
        shopFloor = StorageLocation.builder()
                .id(1L).name("Shop Floor").type(StorageLocationType.SHOP_FLOOR).active(true)
                .isDefaultReceiving(true).isWalkInSaleSource(true).isEcommerceFulfilmentSource(true)
                .build();
        storeRoom = StorageLocation.builder()
                .id(2L).name("Store Room 1").type(StorageLocationType.STORE_ROOM).active(true)
                .build();

        lenient().when(storageLocationRepository.save(any(StorageLocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(storageLocationRepository.saveAndFlush(any(StorageLocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void create_RejectsDuplicateName() {
        when(storageLocationRepository.existsByNameIgnoreCase("Shop Floor")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> storageLocationService.create(
                new StorageLocationRequest("Shop Floor", StorageLocationType.SHOP_FLOOR, null, null)));
        verify(storageLocationRepository, never()).save(any());
    }

    @Test
    void create_TrimsName_DefaultsToActive_NoRoleFlags() {
        when(storageLocationRepository.existsByNameIgnoreCase("  Store Room 2  ")).thenReturn(false);

        StorageLocationResponse response = storageLocationService.create(
                new StorageLocationRequest("  Store Room 2  ", StorageLocationType.STORE_ROOM, 3, null));

        assertEquals("Store Room 2", response.name());
        assertTrue(response.active());
        assertEquals(3, response.lowStockThreshold());
        assertFalse(response.isDefaultReceiving());
        assertFalse(response.isWalkInSaleSource());
        assertFalse(response.isEcommerceFulfilmentSource());
    }

    @Test
    void update_RejectsDuplicateName_OnAnotherLocation() {
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));
        when(storageLocationRepository.existsByNameIgnoreCaseAndIdNot("Shop Floor", 2L)).thenReturn(true);

        assertThrows(BadRequestException.class, () -> storageLocationService.update(2L,
                new StorageLocationRequest("Shop Floor", StorageLocationType.STORE_ROOM, null, null)));
    }

    @Test
    void update_RejectsDeactivation_WhileHoldingDefaultRole() {
        when(storageLocationRepository.findById(1L)).thenReturn(Optional.of(shopFloor));
        when(storageLocationRepository.existsByNameIgnoreCaseAndIdNot("Shop Floor", 1L)).thenReturn(false);

        BadRequestException ex = assertThrows(BadRequestException.class, () -> storageLocationService.update(1L,
                new StorageLocationRequest("Shop Floor", StorageLocationType.SHOP_FLOOR, null, false)));

        assertTrue(ex.getMessage().contains("Reassign the defaults first"));
        assertTrue(shopFloor.isActive());
    }

    @Test
    void update_AppliesFields_LeavesActiveUnchangedWhenNull() {
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));
        when(storageLocationRepository.existsByNameIgnoreCaseAndIdNot("Back Room", 2L)).thenReturn(false);

        StorageLocationResponse response = storageLocationService.update(2L,
                new StorageLocationRequest("Back Room", StorageLocationType.STORE_ROOM, 7, null));

        assertEquals("Back Room", response.name());
        assertEquals(7, response.lowStockThreshold());
        assertTrue(response.active());
    }

    @Test
    void get_ThrowsWhenUnknown() {
        when(storageLocationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> storageLocationService.get(99L));
    }

    @Test
    void updateDefaults_RejectsInactiveTarget() {
        storeRoom.setActive(false);
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));

        assertThrows(BadRequestException.class, () -> storageLocationService.updateDefaults(2L,
                new LocationDefaultsRequest(true, null, null)));
    }

    @Test
    void updateDefaults_MovesFlag_ClearingPreviousHolderFirst() {
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));
        when(storageLocationRepository.findByIsWalkInSaleSourceTrue()).thenReturn(Optional.of(shopFloor));

        StorageLocationResponse response = storageLocationService.updateDefaults(2L,
                new LocationDefaultsRequest(null, true, null));

        assertFalse(shopFloor.isWalkInSaleSource());
        assertTrue(response.isWalkInSaleSource());
        // Previous holder flushed before the target row gains the flag (partial unique index).
        ArgumentCaptor<StorageLocation> flushed = ArgumentCaptor.forClass(StorageLocation.class);
        verify(storageLocationRepository).saveAndFlush(flushed.capture());
        assertEquals(shopFloor, flushed.getValue());
        // Untouched roles stay where they were.
        assertTrue(shopFloor.isDefaultReceiving());
        assertFalse(response.isDefaultReceiving());
    }

    @Test
    void updateDefaults_NoFlushWhenTargetAlreadyHoldsFlag() {
        when(storageLocationRepository.findById(1L)).thenReturn(Optional.of(shopFloor));
        when(storageLocationRepository.findByIsWalkInSaleSourceTrue()).thenReturn(Optional.of(shopFloor));

        StorageLocationResponse response = storageLocationService.updateDefaults(1L,
                new LocationDefaultsRequest(null, true, null));

        assertTrue(response.isWalkInSaleSource());
        verify(storageLocationRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateDefaults_IgnoresFalseAndNullFlags() {
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));

        StorageLocationResponse response = storageLocationService.updateDefaults(2L,
                new LocationDefaultsRequest(false, null, false));

        assertFalse(response.isDefaultReceiving());
        assertFalse(response.isWalkInSaleSource());
        assertFalse(response.isEcommerceFulfilmentSource());
        verify(storageLocationRepository, never()).findByIsDefaultReceivingTrue();
        verify(storageLocationRepository, never()).findByIsEcommerceFulfilmentSourceTrue();
    }

    @Test
    void updateDefaults_MovesAllThreeFlagsAtOnce() {
        when(storageLocationRepository.findById(2L)).thenReturn(Optional.of(storeRoom));
        when(storageLocationRepository.findByIsDefaultReceivingTrue()).thenReturn(Optional.of(shopFloor));
        when(storageLocationRepository.findByIsWalkInSaleSourceTrue()).thenReturn(Optional.of(shopFloor));
        when(storageLocationRepository.findByIsEcommerceFulfilmentSourceTrue()).thenReturn(Optional.of(shopFloor));

        StorageLocationResponse response = storageLocationService.updateDefaults(2L,
                new LocationDefaultsRequest(true, true, true));

        assertTrue(response.isDefaultReceiving());
        assertTrue(response.isWalkInSaleSource());
        assertTrue(response.isEcommerceFulfilmentSource());
        assertFalse(shopFloor.isDefaultReceiving());
        assertFalse(shopFloor.isWalkInSaleSource());
        assertFalse(shopFloor.isEcommerceFulfilmentSource());
    }
}
