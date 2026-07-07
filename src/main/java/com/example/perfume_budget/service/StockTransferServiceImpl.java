package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.inventory.request.StockTransferRequest;
import com.example.perfume_budget.dto.inventory.response.StockTransferResponse;
import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.StockTransfer;
import com.example.perfume_budget.model.StorageLocation;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.StockTransferRepository;
import com.example.perfume_budget.repository.StorageLocationRepository;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.LocationLedgerSync;
import com.example.perfume_budget.service.interfaces.StockTransferService;
import com.example.perfume_budget.utils.AuthUserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockTransferServiceImpl implements StockTransferService {
    private final ProductRepository productRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StockTransferRepository stockTransferRepository;
    private final LocationLedgerSync locationLedgerSync;
    private final AuthUserUtil authUserUtil;
    private final FrontDeskAccessService frontDeskAccessService;

    @Override
    @Transactional
    public StockTransferResponse transfer(StockTransferRequest request) {
        frontDeskAccessService.requireAccess(FrontDeskPermission.STOCK_TRANSFER);
        if (request.fromLocationId().equals(request.toLocationId())) {
            throw new BadRequestException("Source and destination locations must differ.");
        }

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not Found."));
        StorageLocation from = getActiveLocation(request.fromLocationId());
        StorageLocation to = getActiveLocation(request.toLocationId());

        StockTransfer transfer = locationLedgerSync.transfer(
                product, from, to, request.quantity(), request.note(), authUserUtil.getCurrentUser());
        return toResponse(transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransferResponse> history(Long productId, Long locationId, int page, int size) {
        frontDeskAccessService.requireAccess(FrontDeskPermission.STOCK_TRANSFER);
        return stockTransferRepository.findHistory(productId, locationId, PageRequest.of(page, size))
                .map(this::toResponse);
    }

    private StorageLocation getActiveLocation(Long id) {
        StorageLocation location = storageLocationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage location not found."));
        if (!location.isActive()) {
            throw new BadRequestException("Storage location '" + location.getName() + "' is inactive.");
        }
        return location;
    }

    private StockTransferResponse toResponse(StockTransfer transfer) {
        return new StockTransferResponse(
                transfer.getId(),
                transfer.getProduct().getId(),
                transfer.getProduct().getName(),
                transfer.getFromLocation() != null ? transfer.getFromLocation().getId() : null,
                transfer.getFromLocation() != null ? transfer.getFromLocation().getName() : null,
                transfer.getToLocation() != null ? transfer.getToLocation().getId() : null,
                transfer.getToLocation() != null ? transfer.getToLocation().getName() : null,
                transfer.getQuantity(),
                transfer.getTransferType().name(),
                transfer.getMovedBy() != null ? transfer.getMovedBy().getFullName() : "System",
                transfer.getNote(),
                transfer.getCreatedAt()
        );
    }
}
