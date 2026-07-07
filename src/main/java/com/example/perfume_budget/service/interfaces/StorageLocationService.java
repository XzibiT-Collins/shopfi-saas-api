package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.inventory.request.LocationDefaultsRequest;
import com.example.perfume_budget.dto.inventory.request.StorageLocationRequest;
import com.example.perfume_budget.dto.inventory.response.StorageLocationResponse;

import java.util.List;

public interface StorageLocationService {
    StorageLocationResponse create(StorageLocationRequest request);

    StorageLocationResponse update(Long id, StorageLocationRequest request);

    List<StorageLocationResponse> list();

    StorageLocationResponse get(Long id);

    StorageLocationResponse updateDefaults(Long id, LocationDefaultsRequest request);
}
