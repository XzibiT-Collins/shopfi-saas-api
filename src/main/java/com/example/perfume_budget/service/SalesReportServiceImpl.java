package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.sales.ItemsSoldReportResponse;
import com.example.perfume_budget.dto.sales.LocationStockEntry;
import com.example.perfume_budget.dto.sales.ProductSalesDetailResponse;
import com.example.perfume_budget.dto.sales.SaleLineResponse;
import com.example.perfume_budget.dto.sales.SoldProductSummary;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.LocationStock;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.projection.SaleReferenceAmount;
import com.example.perfume_budget.projection.SalesTotals;
import com.example.perfume_budget.projection.SoldProductAggregate;
import com.example.perfume_budget.repository.InventoryMovementRepository;
import com.example.perfume_budget.repository.LocationStockRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.StockTransferRepository;
import com.example.perfume_budget.service.interfaces.SalesReportService;
import com.example.perfume_budget.utils.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesReportServiceImpl implements SalesReportService {

    private static final CurrencyCode DEFAULT_CURRENCY = CurrencyCode.GHS;

    private final InventoryMovementRepository inventoryMovementRepository;
    private final StockTransferRepository stockTransferRepository;
    private final LocationStockRepository locationStockRepository;
    private final ProductRepository productRepository;

    @Override
    public ItemsSoldReportResponse getItemsSold(SalesAnalyticsSource source, LocalDate from, LocalDate to, Pageable pageable) {
        Window window = resolveWindow(from, to);
        SalesAnalyticsSource resolvedSource = source != null ? source : SalesAnalyticsSource.ALL;
        List<InventoryReferenceType> refTypes = referenceTypesFor(resolvedSource);

        Page<SoldProductAggregate> aggregates =
                inventoryMovementRepository.findSoldProductAggregates(refTypes, window.start(), window.end(), pageable);

        Map<Long, List<LocationStockEntry>> byLocation = locationBreakdown(
                aggregates.getContent().stream().map(SoldProductAggregate::getProductId).distinct().toList());

        Page<SoldProductSummary> summaries = aggregates.map(a -> new SoldProductSummary(
                a.getProductId(),
                a.getProductName(),
                a.getSku(),
                a.getImageUrl(),
                a.getQuantitySold() != null ? a.getQuantitySold() : 0L,
                a.getTotalAmount(),
                a.getRemainingStock(),
                a.getLowStockThreshold(),
                byLocation.getOrDefault(a.getProductId(), List.of())));

        SalesTotals totals = inventoryMovementRepository.sumSales(refTypes, window.start(), window.end());

        return new ItemsSoldReportResponse(
                window.from(), window.to(), resolvedSource,
                PaginationUtil.createPageResponse(summaries),
                totals.getTotalQuantity(), totals.getTotalAmount(),
                resolveCurrency(refTypes, window));
    }

    @Override
    public ProductSalesDetailResponse getProductSales(Long productId, SalesAnalyticsSource source,
                                                      LocalDate from, LocalDate to, Pageable pageable) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not Found."));

        Window window = resolveWindow(from, to);
        SalesAnalyticsSource resolvedSource = source != null ? source : SalesAnalyticsSource.ALL;
        List<InventoryReferenceType> refTypes = referenceTypesFor(resolvedSource);

        List<LocationStockEntry> byLocation = locationStockRepository.findByProductIdWithLocation(productId).stream()
                .map(this::toLocationEntry)
                .toList();

        Map<String, BigDecimal> amountByReference = inventoryMovementRepository
                .sumSaleAmountsByReference(productId, refTypes, window.start(), window.end()).stream()
                .collect(Collectors.toMap(
                        r -> referenceKey(r.getReferenceType(), r.getReferenceId()),
                        SaleReferenceAmount::getAmount));

        Page<SaleLineResponse> lines = stockTransferRepository
                .findSaleDeductions(productId, refTypes, window.start(), window.end(), pageable)
                .map(t -> new SaleLineResponse(
                        t.getCreatedAt(),
                        t.getQuantity(),
                        t.getMovedBy() != null ? t.getMovedBy().getFullName() : "System",
                        channelOf(t.getReferenceType()),
                        t.getFromLocation() != null ? t.getFromLocation().getName() : null,
                        t.getBalanceAfter(),
                        t.getReferenceId(),
                        amountByReference.getOrDefault(
                                referenceKey(t.getReferenceType(), t.getReferenceId()), BigDecimal.ZERO)));

        SalesTotals totals = inventoryMovementRepository
                .sumSalesForProduct(productId, refTypes, window.start(), window.end());

        return new ProductSalesDetailResponse(
                product.getId(), product.getName(), product.getSku(), product.getImageUrl(),
                product.getStockQuantity(), byLocation,
                window.from(), window.to(), resolvedSource,
                PaginationUtil.createPageResponse(lines),
                totals.getTotalQuantity(), totals.getTotalAmount(),
                resolveCurrency(refTypes, window));
    }

    // -------------------------------------------------------------------------

    private Map<Long, List<LocationStockEntry>> locationBreakdown(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return locationStockRepository.findByProductIdInWithLocation(productIds).stream()
                .collect(Collectors.groupingBy(
                        ls -> ls.getProduct().getId(),
                        Collectors.mapping(this::toLocationEntry, Collectors.toList())));
    }

    private LocationStockEntry toLocationEntry(LocationStock ls) {
        return new LocationStockEntry(ls.getLocation().getId(), ls.getLocation().getName(), ls.getQuantityOnHand());
    }

    private CurrencyCode resolveCurrency(List<InventoryReferenceType> refTypes, Window window) {
        return inventoryMovementRepository.findSaleCurrencies(refTypes, window.start(), window.end()).stream()
                .findFirst()
                .orElse(DEFAULT_CURRENCY);
    }

    private List<InventoryReferenceType> referenceTypesFor(SalesAnalyticsSource source) {
        return switch (source) {
            case ONLINE -> List.of(InventoryReferenceType.ORDER);
            case WALK_IN -> List.of(InventoryReferenceType.WALK_IN_ORDER);
            case ALL -> List.of(InventoryReferenceType.ORDER, InventoryReferenceType.WALK_IN_ORDER);
        };
    }

    private String channelOf(InventoryReferenceType referenceType) {
        if (referenceType == InventoryReferenceType.WALK_IN_ORDER) {
            return "WALK_IN";
        }
        if (referenceType == InventoryReferenceType.ORDER) {
            return "ONLINE";
        }
        return referenceType != null ? referenceType.name() : null;
    }

    private String referenceKey(InventoryReferenceType referenceType, String referenceId) {
        return referenceType + "|" + referenceId;
    }

    private Window resolveWindow(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) {
            throw new BadRequestException("Both 'from' and 'to' must be provided together");
        }
        if (from != null && from.isAfter(to)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
        LocalDate resolvedFrom = from != null ? from : LocalDate.now();
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        return new Window(resolvedFrom, resolvedTo, resolvedFrom.atStartOfDay(), resolvedTo.atTime(23, 59, 59));
    }

    private record Window(LocalDate from, LocalDate to, LocalDateTime start, LocalDateTime end) {
    }
}
