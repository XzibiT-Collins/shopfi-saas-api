package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.inventory.request.LocationDefaultsRequest;
import com.example.perfume_budget.dto.inventory.request.StorageLocationRequest;
import com.example.perfume_budget.dto.inventory.response.StorageLocationResponse;
import com.example.perfume_budget.service.interfaces.StorageLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/inventory/locations")
@RequiredArgsConstructor
public class StorageLocationController {
    private final StorageLocationService storageLocationService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<CustomApiResponse<StorageLocationResponse>> create(
            @Valid @RequestBody StorageLocationRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(
                "Storage location created",
                storageLocationService.create(request)
        ));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<CustomApiResponse<StorageLocationResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody StorageLocationRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(
                "Storage location updated",
                storageLocationService.update(id, request)
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @GetMapping
    public ResponseEntity<CustomApiResponse<List<StorageLocationResponse>>> list() {
        return ResponseEntity.ok(CustomApiResponse.success(storageLocationService.list()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
    @GetMapping("/{id}")
    public ResponseEntity<CustomApiResponse<StorageLocationResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(CustomApiResponse.success(storageLocationService.get(id)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/defaults")
    public ResponseEntity<CustomApiResponse<StorageLocationResponse>> updateDefaults(
            @PathVariable Long id,
            @RequestBody LocationDefaultsRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(
                "Storage location defaults updated",
                storageLocationService.updateDefaults(id, request)
        ));
    }
}
