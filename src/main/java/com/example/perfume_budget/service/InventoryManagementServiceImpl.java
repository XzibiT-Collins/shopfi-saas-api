package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.inventory.request.InventoryAdjustmentRequest;
import com.example.perfume_budget.dto.inventory.request.InventoryReceiptRequest;
import com.example.perfume_budget.dto.inventory.response.InventoryLayerResponse;
import com.example.perfume_budget.dto.inventory.response.InventoryMovementResponse;
import com.example.perfume_budget.dto.inventory.response.InventorySummaryResponse;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.dto.inventory.response.LocationStockLine;
import com.example.perfume_budget.dto.inventory.response.ProductStockByLocationResponse;
import com.example.perfume_budget.repository.InventoryAllocationRepository;
import com.example.perfume_budget.repository.InventoryLayerRepository;
import com.example.perfume_budget.repository.InventoryMovementRepository;
import com.example.perfume_budget.repository.LocationStockRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.LocationLedgerSync;
import com.example.perfume_budget.utils.AuthUserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryManagementServiceImpl implements InventoryManagementService {
    private final ProductRepository productRepository;
    private final InventoryLayerRepository inventoryLayerRepository;
    private final InventoryAllocationRepository inventoryAllocationRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final LocationStockRepository locationStockRepository;
    private final AuthUserUtil authUserUtil;
    private final BookkeepingService bookkeepingService;
    private final LocationLedgerSync locationLedgerSync;

    @Override
    @Transactional
//    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public InventorySummaryResponse receiveStock(InventoryReceiptRequest request) {
        Product product = getProduct(request.productId());
        validatePositiveAmounts(request.unitCost(), request.unitSellingPrice());

        // First-ever receipt for a product that holds no stock is its opening stock.
        boolean firstReceipt = !inventoryLayerRepository.existsByProductId(product.getId())
                && (product.getStockQuantity() == null || product.getStockQuantity() == 0);
        InventoryLayerSourceType sourceType = firstReceipt
                ? InventoryLayerSourceType.OPENING_STOCK
                : InventoryLayerSourceType.PURCHASE;
        String lineKey = (firstReceipt ? "opening:" : "receipt:") + product.getId();

        Product updated = createLayer(product,
                request.quantity(),
                request.unitCost(),
                request.unitSellingPrice(),
                sourceType,
                request.reference(),
                request.note(),
                request.receivedAt() != null ? request.receivedAt() : LocalDateTime.now(),
                InventoryMovementType.RECEIPT,
                InventoryReferenceType.RECEIPT,
                request.reference(),
                lineKey);

        bookkeepingService.recordInventoryPurchase(
                updated,
                request.quantity(),
                scale(request.unitCost().multiply(BigDecimal.valueOf(request.quantity())))
        );
        return buildSummary(updated);
    }

    @Override
    @Transactional
//    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public InventorySummaryResponse adjustInventory(InventoryAdjustmentRequest request) {
        Product product = getProduct(request.productId());
        return switch (request.direction()) {
            case INCREASE -> handleAdjustmentIncrease(product, request);
            case DECREASE -> handleAdjustmentDecrease(product, request);
        };
    }

    @Override
    @Transactional
    public InventorySummaryResponse getProductInventorySummary(Long productId) {
        Product product = getProduct(productId);
        ensureLegacyLayer(product);
        return buildSummary(product);
    }

    @Override
    @Transactional
    public List<InventoryMovementResponse> getProductInventoryHistory(Long productId) {
        Product product = getProduct(productId);
        ensureLegacyLayer(product);
        return inventoryMovementRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId).stream()
                .map(this::toMovementResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductStockByLocationResponse getProductStockByLocation(Long productId) {
        Product product = getProduct(productId);
        List<LocationStockLine> locations = locationStockRepository.findByProductIdWithLocation(productId).stream()
                .map(ls -> new LocationStockLine(
                        ls.getLocation().getId(),
                        ls.getLocation().getName(),
                        ls.getLocation().getType().name(),
                        ls.getQuantityOnHand()))
                .toList();

        int sumOfLocations = locations.stream().mapToInt(LocationStockLine::quantityOnHand).sum();
        int outstandingReserved = inventoryAllocationRepository.sumReservedQuantityByProductId(productId);
        int globalStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;

        // Outstanding reservations have already left global stock but not the physical ledger —
        // a tolerated delta, not drift (ADR-002).
        boolean balancesMatchGlobal = sumOfLocations == globalStock + outstandingReserved;

        return new ProductStockByLocationResponse(
                product.getId(),
                product.getName(),
                globalStock,
                outstandingReserved,
                locations,
                balancesMatchGlobal
        );
    }

    @Override
    @Transactional
//    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public void reserveOrderInventory(String orderNumber, List<OrderItem> orderItems) {
        for (OrderItem item : orderItems) {
            consumeInventory(getProduct(item.getProductId()),
                    item.getQuantity(),
                    InventoryReferenceType.ORDER,
                    orderNumber,
                    buildLineKey(item.getProductId()),
                    "Reserved for order " + orderNumber,
                    true);
        }
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public void releaseOrderInventory(String orderNumber) {
        List<InventoryAllocation> allocations = inventoryAllocationRepository
                .findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
                        InventoryReferenceType.ORDER,
                        orderNumber,
                        InventoryAllocationStatus.RESERVED
                );

        allocations.forEach(allocation -> {
            InventoryLayer layer = allocation.getLayer();
            layer.setRemainingQuantity(layer.getRemainingQuantity() + allocation.getQuantity());
            inventoryLayerRepository.save(layer);
            allocation.setStatus(InventoryAllocationStatus.RELEASED);
            inventoryAllocationRepository.save(allocation);
            recordMovement(layer.getProduct(),
                    layer,
                    InventoryMovementType.RELEASE,
                    InventoryReferenceType.ORDER,
                    orderNumber,
                    allocation.getReferenceLineKey(),
                    allocation.getQuantity(),
                    allocation.getUnitCost(),
                    allocation.getUnitSellingPrice(),
                    "Released reservation for order " + orderNumber);
            refreshProductInventoryState(layer.getProduct());
        });
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public void finalizeReservedOrder(Order order) {
        List<InventoryAllocation> allocations = inventoryAllocationRepository
                .findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
                        InventoryReferenceType.ORDER,
                        order.getOrderNumber(),
                        InventoryAllocationStatus.RESERVED
                );

        Map<String, BigDecimal> totalCostByLine = new LinkedHashMap<>();
        Map<String, Integer> quantityByLine = new LinkedHashMap<>();
        Map<Long, Product> productById = new LinkedHashMap<>();
        Map<Long, Integer> quantityByProduct = new LinkedHashMap<>();

        allocations.forEach(allocation -> {
            allocation.setStatus(InventoryAllocationStatus.CONSUMED);
            inventoryAllocationRepository.save(allocation);
            productById.putIfAbsent(allocation.getProduct().getId(), allocation.getProduct());
            quantityByProduct.merge(allocation.getProduct().getId(), allocation.getQuantity(), Integer::sum);
            recordMovement(allocation.getProduct(),
                    allocation.getLayer(),
                    InventoryMovementType.SALE,
                    InventoryReferenceType.ORDER,
                    order.getOrderNumber(),
                    allocation.getReferenceLineKey(),
                    allocation.getQuantity(),
                    allocation.getUnitCost(),
                    allocation.getUnitSellingPrice(),
                    "Consumed for order " + order.getOrderNumber());

            BigDecimal lineCost = allocation.getUnitCost().multiply(BigDecimal.valueOf(allocation.getQuantity()));
            totalCostByLine.merge(allocation.getReferenceLineKey(), lineCost, BigDecimal::add);
            quantityByLine.merge(allocation.getReferenceLineKey(), allocation.getQuantity(), Integer::sum);
        });

        for (OrderItem item : order.getItems()) {
            String lineKey = buildLineKey(item.getProductId());
            BigDecimal totalCost = totalCostByLine.get(lineKey);
            Integer quantity = quantityByLine.get(lineKey);
            if (totalCost != null && quantity != null && quantity > 0) {
                item.setCostPrice(new Money(scale(totalCost.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_EVEN)),
                        item.getUnitPrice().getCurrencyCode()));
            }
        }

        quantityByProduct.forEach((productId, consumedQuantity) ->
                locationLedgerSync.deductForEcommerceSale(productById.get(productId), consumedQuantity,
                        order.getOrderNumber(), "Fulfilled order " + order.getOrderNumber()));

        refreshProducts(order.getItems().stream().map(OrderItem::getProductId).distinct().toList());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public void consumeWalkInInventory(String orderNumber, List<WalkInOrderItem> items) {
        for (WalkInOrderItem item : items) {
            InventoryConsumption consumption = consumeInventory(getProduct(item.getProductId()),
                    item.getQuantity(),
                    InventoryReferenceType.WALK_IN_ORDER,
                    orderNumber,
                    buildLineKey(item.getProductId()),
                    "Consumed for walk-in order " + orderNumber,
                    false);
            item.setCostPrice(scale(consumption.totalCost().divide(
                    BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_EVEN)));
        }
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public InventoryConsumption consumeInventory(Product product,
                                                 int quantity,
                                                 InventoryReferenceType referenceType,
                                                 String referenceId,
                                                 String referenceLineKey,
                                                 String note,
                                                 boolean createReservation) {
        ensureLegacyLayer(product);
        List<InventoryLayer> layers = inventoryLayerRepository
                .findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(product.getId(), 0);

        int required = quantity;
        List<InventoryConsumptionLine> lines = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (InventoryLayer layer : layers) {
            if (required == 0) {
                break;
            }

            int available = layer.getRemainingQuantity();
            if (available <= 0) {
                continue;
            }

            int consumed = Math.min(available, required);
            layer.setRemainingQuantity(available - consumed);
            inventoryLayerRepository.save(layer);
            required -= consumed;

            BigDecimal lineCost = layer.getUnitCost().multiply(BigDecimal.valueOf(consumed));
            totalCost = totalCost.add(lineCost);
            lines.add(new InventoryConsumptionLine(layer.getId(), consumed, layer.getUnitCost(), layer.getUnitSellingPrice()));

            if (createReservation) {
                inventoryAllocationRepository.save(InventoryAllocation.builder()
                        .layer(layer)
                        .product(product)
                        .referenceType(referenceType)
                        .referenceId(referenceId)
                        .referenceLineKey(referenceLineKey)
                        .quantity(consumed)
                        .unitCost(layer.getUnitCost())
                        .unitSellingPrice(layer.getUnitSellingPrice())
                        .currencyCode(layer.getCurrencyCode())
                        .status(InventoryAllocationStatus.RESERVED)
                        .build());

                recordMovement(product, layer, InventoryMovementType.RESERVATION, referenceType, referenceId,
                        referenceLineKey, consumed, layer.getUnitCost(), layer.getUnitSellingPrice(), note);
            } else {
                recordMovement(product, layer, resolveImmediateConsumptionType(referenceType), referenceType, referenceId,
                        referenceLineKey, consumed, layer.getUnitCost(), layer.getUnitSellingPrice(), note);
            }
        }

        if (required > 0) {
            throw new BadRequestException("Insufficient stock for: " + product.getName());
        }

        // Location ledger only moves on physical exits; reservations stay global-only (ADR-002).
        if (!createReservation) {
            switch (referenceType) {
                case ADJUSTMENT, CONVERSION -> locationLedgerSync.deductAtDefaultReceiving(product, quantity, referenceType, referenceId, note);
                default -> locationLedgerSync.deductForWalkInSale(product, quantity, referenceId, note);
            }
        }

        refreshProductInventoryState(product);
        return new InventoryConsumption(lines, scale(totalCost));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public Product createConversionLayer(Product product,
                                         int quantity,
                                         BigDecimal unitCost,
                                         BigDecimal unitSellingPrice,
                                         InventoryLayerSourceType sourceType,
                                         String sourceReference,
                                         String note,
                                         LocalDateTime receivedAt) {
        return createLayer(product,
                quantity,
                unitCost,
                unitSellingPrice,
                sourceType,
                sourceReference,
                note,
                receivedAt,
                InventoryMovementType.CONVERSION_IN,
                InventoryReferenceType.CONVERSION,
                sourceReference,
                "conversion:" + product.getId());
    }

    private InventorySummaryResponse handleAdjustmentIncrease(Product product, InventoryAdjustmentRequest request) {
        if (request.unitCost() == null || request.unitSellingPrice() == null) {
            throw new BadRequestException("Adjustment increase requires unit cost and unit selling price.");
        }
        validatePositiveAmounts(request.unitCost(), request.unitSellingPrice());

        Product updated = createLayer(product,
                request.quantity(),
                request.unitCost(),
                request.unitSellingPrice(),
                InventoryLayerSourceType.ADJUSTMENT_IN,
                request.reference(),
                request.note() != null ? request.note() : request.reason(),
                LocalDateTime.now(),
                InventoryMovementType.ADJUSTMENT_IN,
                InventoryReferenceType.ADJUSTMENT,
                request.reference() != null ? request.reference() : request.reason(),
                "adjustment:" + product.getId());

        bookkeepingService.recordInventoryAdjustmentIn(
                updated,
                request.quantity(),
                scale(request.unitCost().multiply(BigDecimal.valueOf(request.quantity())))
        );
        return buildSummary(updated);
    }

    private InventorySummaryResponse handleAdjustmentDecrease(Product product, InventoryAdjustmentRequest request) {
        InventoryConsumption consumption = consumeInventory(product,
                request.quantity(),
                InventoryReferenceType.ADJUSTMENT,
                request.reference() != null ? request.reference() : request.reason(),
                buildLineKey(product.getId()),
                request.note() != null ? request.note() : request.reason(),
                false);

        bookkeepingService.recordInventoryAdjustment(product, request.quantity(), consumption.totalCost());
        return buildSummary(productRepository.findById(product.getId()).orElse(product));
    }

    private Product createLayer(Product product,
                                int quantity,
                                BigDecimal unitCost,
                                BigDecimal unitSellingPrice,
                                InventoryLayerSourceType sourceType,
                                String sourceReference,
                                String note,
                                LocalDateTime receivedAt,
                                InventoryMovementType movementType,
                                InventoryReferenceType referenceType,
                                String referenceId,
                                String referenceLineKey) {
        InventoryLayer layer = inventoryLayerRepository.save(InventoryLayer.builder()
                .product(product)
                .sourceType(sourceType)
                .sourceReference(sourceReference)
                .note(note)
                .receivedQuantity(quantity)
                .remainingQuantity(quantity)
                .unitCost(scale(unitCost))
                .unitSellingPrice(scale(unitSellingPrice))
                .currencyCode(resolveCurrency(product))
                .receivedAt(receivedAt)
                .createdBy(getCurrentActor())
                .build());

        recordMovement(product, layer, movementType, referenceType, referenceId, referenceLineKey, quantity,
                layer.getUnitCost(), layer.getUnitSellingPrice(), note);

        locationLedgerSync.increaseAtDefaultReceiving(product, quantity,
                movementType == InventoryMovementType.RECEIPT ? StockTransferType.RECEIPT : StockTransferType.ADJUSTMENT,
                referenceType, referenceId, note);
        return refreshProductInventoryState(product);
    }

    private Product refreshProductInventoryState(Product product) {
        List<InventoryLayer> layers = inventoryLayerRepository.findByProductIdOrderByReceivedAtAscIdAsc(product.getId());
        int stock = layers.stream().mapToInt(InventoryLayer::getRemainingQuantity).sum();
        product.setStockQuantity(stock);

        layers.stream()
                .filter(layer -> layer.getRemainingQuantity() > 0)
                .min(Comparator.comparing(InventoryLayer::getReceivedAt).thenComparing(InventoryLayer::getId))
                .ifPresent(activeLayer -> {
                    product.setPrice(new Money(activeLayer.getUnitSellingPrice(), activeLayer.getCurrencyCode()));
                    product.setCostPrice(new Money(activeLayer.getUnitCost(), activeLayer.getCurrencyCode()));
                });

        return productRepository.save(product);
    }

    private void refreshProducts(List<Long> productIds) {
        productIds.forEach(id -> refreshProductInventoryState(getProduct(id)));
    }

    private void ensureLegacyLayer(Product product) {
        if (inventoryLayerRepository.existsByProductId(product.getId()) || product.getStockQuantity() == null || product.getStockQuantity() <= 0) {
            return;
        }

        BigDecimal unitCost = product.getCostPrice() != null ? product.getCostPrice().getAmount() : BigDecimal.ZERO;
        BigDecimal unitSellingPrice = product.getPrice() != null ? product.getPrice().getAmount() : BigDecimal.ZERO;
        InventoryLayer layer = inventoryLayerRepository.save(InventoryLayer.builder()
                .product(product)
                .sourceType(InventoryLayerSourceType.LEGACY_BALANCE)
                .sourceReference("LEGACY-" + product.getId())
                .note("Bootstrapped from legacy product balance")
                .receivedQuantity(product.getStockQuantity())
                .remainingQuantity(product.getStockQuantity())
                .unitCost(scale(unitCost))
                .unitSellingPrice(scale(unitSellingPrice))
                .currencyCode(resolveCurrency(product))
                .receivedAt(LocalDateTime.now())
                .createdBy("System")
                .build());

        recordMovement(product, layer, InventoryMovementType.LEGACY_BOOTSTRAP, InventoryReferenceType.LEGACY,
                "LEGACY-" + product.getId(), buildLineKey(product.getId()), product.getStockQuantity(),
                layer.getUnitCost(), layer.getUnitSellingPrice(), "Bootstrapped from legacy stock balance");
        refreshProductInventoryState(product);
        log.info("Bootstrapped legacy inventory layer for product {}", product.getId());
    }

    private void recordMovement(Product product,
                                InventoryLayer layer,
                                InventoryMovementType movementType,
                                InventoryReferenceType referenceType,
                                String referenceId,
                                String referenceLineKey,
                                int quantity,
                                BigDecimal unitCost,
                                BigDecimal unitSellingPrice,
                                String note) {
        inventoryMovementRepository.save(InventoryMovement.builder()
                .product(product)
                .layer(layer)
                .movementType(movementType)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .referenceLineKey(referenceLineKey)
                .quantity(quantity)
                .unitCost(scale(unitCost))
                .unitSellingPrice(scale(unitSellingPrice))
                .currencyCode(resolveCurrency(product))
                .note(note)
                .recordedBy(getCurrentActor())
                .build());
    }

    private InventorySummaryResponse buildSummary(Product product) {
        List<InventoryLayer> layers = inventoryLayerRepository.findByProductIdOrderByReceivedAtAscIdAsc(product.getId());
        Product refreshed = productRepository.findById(product.getId()).orElse(product);
        return new InventorySummaryResponse(
                refreshed.getId(),
                refreshed.getName(),
                refreshed.getStockQuantity(),
                refreshed.getCostPrice() != null ? refreshed.getCostPrice().toString() : "0.00",
                refreshed.getPrice() != null ? refreshed.getPrice().toString() : "0.00",
                layers.stream().map(this::toLayerResponse).toList()
        );
    }

    private InventoryLayerResponse toLayerResponse(InventoryLayer layer) {
        return new InventoryLayerResponse(
                layer.getId(),
                layer.getReceivedQuantity(),
                layer.getRemainingQuantity(),
                new Money(layer.getUnitCost(), layer.getCurrencyCode()).toString(),
                new Money(layer.getUnitSellingPrice(), layer.getCurrencyCode()).toString(),
                layer.getSourceType().name(),
                layer.getSourceReference(),
                layer.getReceivedAt()
        );
    }

    private InventoryMovementResponse toMovementResponse(InventoryMovement movement) {
        return new InventoryMovementResponse(
                movement.getId(),
                movement.getMovementType().name(),
                movement.getQuantity(),
                new Money(movement.getUnitCost(), movement.getCurrencyCode()).toString(),
                new Money(movement.getUnitSellingPrice(), movement.getCurrencyCode()).toString(),
                movement.getReferenceType().name(),
                movement.getReferenceId(),
                movement.getRecordedBy(),
                movement.getNote(),
                movement.getCreatedAt()
        );
    }

    private Product getProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not Found."));
    }

    private CurrencyCode resolveCurrency(Product product) {
        if (product.getPrice() != null) {
            return product.getPrice().getCurrencyCode();
        }
        if (product.getCostPrice() != null) {
            return product.getCostPrice().getCurrencyCode();
        }
        return CurrencyCode.GHS;
    }

    private InventoryMovementType resolveImmediateConsumptionType(InventoryReferenceType referenceType) {
        return switch (referenceType) {
            case WALK_IN_ORDER -> InventoryMovementType.SALE;
            case ADJUSTMENT -> InventoryMovementType.ADJUSTMENT_OUT;
            case CONVERSION -> InventoryMovementType.CONVERSION_OUT;
            default -> InventoryMovementType.SALE;
        };
    }

    private void validatePositiveAmounts(BigDecimal unitCost, BigDecimal unitSellingPrice) {
        if (unitCost.compareTo(BigDecimal.ZERO) < 0 || unitSellingPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Inventory amounts cannot be negative.");
        }
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    private String buildLineKey(Long productId) {
        return "product:" + productId;
    }

    private String getCurrentActor() {
        User currentUser = authUserUtil.getCurrentUser();
        return currentUser != null ? currentUser.getFullName() : "System";
    }
}
