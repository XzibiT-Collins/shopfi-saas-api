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
import com.example.perfume_budget.service.interfaces.LocationLedgerSync;
import com.example.perfume_budget.utils.AuthUserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationLedgerSyncImpl implements LocationLedgerSync {
    private final LocationStockRepository locationStockRepository;
    private final StockTransferRepository stockTransferRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final AuthUserUtil authUserUtil;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public void applyDelta(Product product, StorageLocation location, int delta, StockTransferType type,
                           InventoryReferenceType referenceType, String referenceId, String note, User movedBy) {
        if (delta == 0) {
            return;
        }

        LocationStock stock = lockOrCreate(product, location);
        // Negative balances allowed: physical restock not yet recorded as a transfer (ADR-003).
        stock.setQuantityOnHand(stock.getQuantityOnHand() + delta);
        locationStockRepository.save(stock);

        stockTransferRepository.save(StockTransfer.builder()
                .product(product)
                .fromLocation(delta < 0 ? location : null)
                .toLocation(delta > 0 ? location : null)
                .quantity(Math.abs(delta))
                .movedBy(movedBy)
                .transferType(type)
                .balanceAfter(stock.getQuantityOnHand())
                .referenceType(referenceType)
                .referenceId(referenceId)
                .note(note)
                .build());

        if (delta < 0 && location.getType() == StorageLocationType.SHOP_FLOOR) {
            eventPublisher.publishEvent(new ShopFloorStockEvent(product.getId(), product.getName()));
        }
    }

    @Override
    @Transactional
    public void increaseAtDefaultReceiving(Product product, int quantity, StockTransferType type,
                                           InventoryReferenceType referenceType, String referenceId, String note) {
        resolve(storageLocationRepository.findByIsDefaultReceivingTrue(), "default receiving")
                .ifPresent(location -> applyDelta(product, location, quantity, type, referenceType, referenceId, note,
                        authUserUtil.getCurrentUser()));
    }

    @Override
    @Transactional
    public void deductForWalkInSale(Product product, int quantity, String referenceId, String note) {
        resolve(storageLocationRepository.findByIsWalkInSaleSourceTrue(), "walk-in sale source")
                .ifPresent(location -> applyDelta(product, location, -quantity, StockTransferType.SALE_DEDUCTION,
                        InventoryReferenceType.WALK_IN_ORDER, referenceId, note, authUserUtil.getCurrentUser()));
    }

    @Override
    @Transactional
    public void deductForEcommerceSale(Product product, int quantity, String referenceId, String note) {
        resolve(storageLocationRepository.findByIsEcommerceFulfilmentSourceTrue(), "e-commerce fulfilment source")
                .ifPresent(location -> applyDelta(product, location, -quantity, StockTransferType.SALE_DEDUCTION,
                        InventoryReferenceType.ORDER, referenceId, note, authUserUtil.getCurrentUser()));
    }

    @Override
    @Transactional
    public void deductAtDefaultReceiving(Product product, int quantity, InventoryReferenceType referenceType,
                                         String referenceId, String note) {
        resolve(storageLocationRepository.findByIsDefaultReceivingTrue(), "default receiving")
                .ifPresent(location -> applyDelta(product, location, -quantity, StockTransferType.ADJUSTMENT,
                        referenceType, referenceId, note, authUserUtil.getCurrentUser()));
    }

    @Override
    @Transactional
    public StockTransfer transfer(Product product, StorageLocation from, StorageLocation to, int quantity, String note, User movedBy) {
        // Lock both balance rows in a stable order to avoid deadlocks between concurrent transfers.
        LocationStock fromStock;
        LocationStock toStock;
        if (from.getId() < to.getId()) {
            fromStock = lockOrCreate(product, from);
            toStock = lockOrCreate(product, to);
        } else {
            toStock = lockOrCreate(product, to);
            fromStock = lockOrCreate(product, from);
        }

        // Unlike sales, a transfer moves real units the mover is holding — source must cover it.
        if (fromStock.getQuantityOnHand() < quantity) {
            throw new BadRequestException("Insufficient stock at " + from.getName() + ": "
                    + fromStock.getQuantityOnHand() + " on hand, " + quantity + " requested.");
        }

        fromStock.setQuantityOnHand(fromStock.getQuantityOnHand() - quantity);
        toStock.setQuantityOnHand(toStock.getQuantityOnHand() + quantity);
        locationStockRepository.save(fromStock);
        locationStockRepository.save(toStock);

        StockTransfer transfer = stockTransferRepository.save(StockTransfer.builder()
                .product(product)
                .fromLocation(from)
                .toLocation(to)
                .quantity(quantity)
                .movedBy(movedBy)
                .transferType(StockTransferType.TRANSFER)
                .balanceAfter(fromStock.getQuantityOnHand())
                .note(note)
                .build());

        if (from.getType() == StorageLocationType.SHOP_FLOOR) {
            eventPublisher.publishEvent(new ShopFloorStockEvent(product.getId(), product.getName()));
        }
        return transfer;
    }

    private LocationStock lockOrCreate(Product product, StorageLocation location) {
        return locationStockRepository.findForUpdate(product.getId(), location.getId())
                .orElseGet(() -> locationStockRepository.save(LocationStock.builder()
                        .product(product)
                        .location(location)
                        .quantityOnHand(0)
                        .build()));
    }

    private Optional<StorageLocation> resolve(Optional<StorageLocation> location, String role) {
        if (location.isEmpty()) {
            log.warn("No {} location configured; skipping location ledger update.", role);
        }
        return location;
    }
}
