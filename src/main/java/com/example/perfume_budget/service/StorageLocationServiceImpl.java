package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.inventory.request.LocationDefaultsRequest;
import com.example.perfume_budget.dto.inventory.request.StorageLocationRequest;
import com.example.perfume_budget.dto.inventory.response.StorageLocationResponse;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.StorageLocation;
import com.example.perfume_budget.repository.StorageLocationRepository;
import com.example.perfume_budget.service.interfaces.StorageLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageLocationServiceImpl implements StorageLocationService {
    private final StorageLocationRepository storageLocationRepository;

    @Override
    @Transactional
    public StorageLocationResponse create(StorageLocationRequest request) {
        if (storageLocationRepository.existsByNameIgnoreCase(request.name())) {
            throw new BadRequestException("A storage location named '" + request.name() + "' already exists.");
        }

        StorageLocation location = storageLocationRepository.save(StorageLocation.builder()
                .name(request.name().trim())
                .type(request.type())
                .lowStockThreshold(request.lowStockThreshold())
                .active(request.active() == null || request.active())
                .build());
        return toResponse(location);
    }

    @Override
    @Transactional
    public StorageLocationResponse update(Long id, StorageLocationRequest request) {
        StorageLocation location = getLocation(id);
        if (storageLocationRepository.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw new BadRequestException("A storage location named '" + request.name() + "' already exists.");
        }
        if (request.active() != null && !request.active() && holdsAnyDefaultFlag(location)) {
            throw new BadRequestException("Cannot deactivate a location that is a default receiving or sale source. Reassign the defaults first.");
        }

        location.setName(request.name().trim());
        location.setType(request.type());
        location.setLowStockThreshold(request.lowStockThreshold());
        if (request.active() != null) {
            location.setActive(request.active());
        }
        return toResponse(storageLocationRepository.save(location));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StorageLocationResponse> list() {
        return storageLocationRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StorageLocationResponse get(Long id) {
        return toResponse(getLocation(id));
    }

    @Override
    @Transactional
    public StorageLocationResponse updateDefaults(Long id, LocationDefaultsRequest request) {
        StorageLocation target = getLocation(id);
        if (!target.isActive()) {
            throw new BadRequestException("Cannot assign defaults to an inactive location.");
        }

        if (Boolean.TRUE.equals(request.isDefaultReceiving())) {
            moveFlag(storageLocationRepository.findByIsDefaultReceivingTrue(), target,
                    holder -> holder.setDefaultReceiving(false), () -> target.setDefaultReceiving(true));
        }
        if (Boolean.TRUE.equals(request.isWalkInSaleSource())) {
            moveFlag(storageLocationRepository.findByIsWalkInSaleSourceTrue(), target,
                    holder -> holder.setWalkInSaleSource(false), () -> target.setWalkInSaleSource(true));
        }
        if (Boolean.TRUE.equals(request.isEcommerceFulfilmentSource())) {
            moveFlag(storageLocationRepository.findByIsEcommerceFulfilmentSourceTrue(), target,
                    holder -> holder.setEcommerceFulfilmentSource(false), () -> target.setEcommerceFulfilmentSource(true));
        }

        return toResponse(storageLocationRepository.save(target));
    }

    private void moveFlag(Optional<StorageLocation> currentHolder,
                          StorageLocation target,
                          java.util.function.Consumer<StorageLocation> clearFlag,
                          Runnable setFlag) {
        currentHolder
                .filter(holder -> !holder.getId().equals(target.getId()))
                .ifPresent(holder -> {
                    clearFlag.accept(holder);
                    // Flush the clear before setting the flag on the target so the partial
                    // unique index never sees two rows with the flag at once.
                    storageLocationRepository.saveAndFlush(holder);
                });
        setFlag.run();
    }

    private boolean holdsAnyDefaultFlag(StorageLocation location) {
        return location.isDefaultReceiving() || location.isWalkInSaleSource() || location.isEcommerceFulfilmentSource();
    }

    private StorageLocation getLocation(Long id) {
        return storageLocationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage location not found."));
    }

    private StorageLocationResponse toResponse(StorageLocation location) {
        return new StorageLocationResponse(
                location.getId(),
                location.getName(),
                location.getType().name(),
                location.isActive(),
                location.getLowStockThreshold(),
                location.isDefaultReceiving(),
                location.isWalkInSaleSource(),
                location.isEcommerceFulfilmentSource(),
                location.getCreatedAt(),
                location.getUpdatedAt()
        );
    }
}
