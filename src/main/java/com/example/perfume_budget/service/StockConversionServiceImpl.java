package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.product.response.ProductVariantSummaryResponse;
import com.example.perfume_budget.dto.product.response.StockConversionResponse;
import com.example.perfume_budget.enums.ConversionDirection;
import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.ProductMapper;
import com.example.perfume_budget.mapper.StockConversionMapper;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.StockConversion;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.StockConversionRepository;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.StockConversionService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.ProductCodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockConversionServiceImpl implements StockConversionService {
    private final ProductRepository productRepository;
    private final StockConversionRepository stockConversionRepository;
    private final BookkeepingService bookkeepingService;
    private final AuthUserUtil authUserUtil;
    private final InventoryManagementService inventoryManagementService;
    private final FrontDeskAccessService frontDeskAccessService;

    @Override
    public List<ProductVariantSummaryResponse> getReverseConversionTargetVariants(Long sourceProductId) {
        frontDeskAccessService.requireAccess(FrontDeskPermission.STOCK_CONVERSION);
        Product sourceProduct = productRepository.findById(sourceProductId)
                .orElseThrow(() -> new ResourceNotFoundException("Source product not found"));

        if (!Boolean.TRUE.equals(sourceProduct.getIsBaseUnit())) {
            throw new BadRequestException("Reverse conversion variants can only be fetched from a base unit source.");
        }

        if (sourceProduct.getFamily() == null) {
            throw new BadRequestException("Source product is not attached to a product family.");
        }

        return productRepository.findByFamily(sourceProduct.getFamily()).stream()
                .filter(product -> !product.getId().equals(sourceProductId))
                .map(ProductMapper::toProductVariantSummary)
                .toList();
    }

    @Override
    @Transactional
    public StockConversionResponse convertForward(Long sourceProductId, Integer sourceQuantity, String notes) {
        frontDeskAccessService.requireAccess(FrontDeskPermission.STOCK_CONVERSION);
        Product sourceProduct = productRepository.findById(sourceProductId)
                .orElseThrow(() -> new ResourceNotFoundException("Source product not found"));

        if (Boolean.TRUE.equals(sourceProduct.getIsBaseUnit())) {
            throw new BadRequestException("Forward conversion must start from a bulk unit, not a base unit.");
        }

        if (sourceProduct.getStockQuantity() < sourceQuantity) {
            throw new BadRequestException("Insufficient stock for conversion.");
        }

        if (sourceProduct.getFamily() == null || sourceProduct.getFamily().getBaseUnit() == null) {
            throw new BadRequestException("Product family or base unit not properly configured.");
        }

        Product targetProduct = sourceProduct.getFamily().getBaseUnit();
        Integer conversionFactor = sourceProduct.getConversionFactor();
        Integer targetQuantityAdded = sourceQuantity * conversionFactor;
        String conversionReference = "CONV-FWD-" + sourceProductId + "-" + System.currentTimeMillis();

        InventoryManagementService.InventoryConsumption consumption = inventoryManagementService.consumeInventory(
                sourceProduct,
                sourceQuantity,
                com.example.perfume_budget.enums.InventoryReferenceType.CONVERSION,
                conversionReference,
                "product:" + sourceProductId,
                "Forward conversion from " + sourceProduct.getName(),
                false
        );

        BigDecimal fromCostValue = consumption.totalCost();
        for (InventoryManagementService.InventoryConsumptionLine line : consumption.lines()) {
            inventoryManagementService.createConversionLayer(
                    targetProduct,
                    line.quantity() * conversionFactor,
                    line.unitCost().divide(BigDecimal.valueOf(conversionFactor), 2, RoundingMode.HALF_EVEN),
                    resolveForwardTargetSellingPrice(sourceProduct, targetProduct, line.unitSellingPrice(), conversionFactor),
                    com.example.perfume_budget.enums.InventoryLayerSourceType.CONVERSION_IN,
                    conversionReference,
                    notes,
                    LocalDateTime.now()
            );
        }

        BigDecimal toCostValue = fromCostValue;
        BigDecimal variance = fromCostValue.subtract(toCostValue);

        User currentUser = authUserUtil.getCurrentUser();

        StockConversion conversion = StockConversion.builder()
                .conversionNumber(ProductCodeGenerator.generateConversionNumber("CONV-FWD"))
                .fromProduct(sourceProduct)
                .toProduct(targetProduct)
                .fromQuantity(sourceQuantity)
                .toQuantity(targetQuantityAdded)
                .fromCostValue(fromCostValue)
                .toCostValue(toCostValue)
                .varianceAmount(variance)
                .direction(ConversionDirection.FORWARD)
                .convertedBy(currentUser)
                .notes(notes)
                .convertedAt(LocalDateTime.now())
                .build();

        StockConversion savedConversion = stockConversionRepository.save(conversion);
        bookkeepingService.recordStockConversion(savedConversion);

        return StockConversionMapper.toResponse(savedConversion);
    }

    @Override
    @Transactional
    public StockConversionResponse convertReverse(Long sourceProductId, Integer sourceQuantity, Long targetProductId, String notes) {
        frontDeskAccessService.requireAccess(FrontDeskPermission.STOCK_CONVERSION);
        if (targetProductId == null) {
            throw new BadRequestException("Target product ID is required for reverse conversion.");
        }

        Product sourceProduct = productRepository.findById(sourceProductId)
                .orElseThrow(() -> new ResourceNotFoundException("Source product not found"));
                
        Product targetProduct = productRepository.findById(targetProductId)
                .orElseThrow(() -> new ResourceNotFoundException("Target product not found"));

        if (!Boolean.TRUE.equals(sourceProduct.getIsBaseUnit())) {
            throw new BadRequestException("Reverse conversion must start from a base unit.");
        }

        if (sourceProduct.getStockQuantity() < sourceQuantity) {
            throw new BadRequestException("Insufficient stock for conversion.");
        }

        if (targetProduct.getFamily() == null || !targetProduct.getFamily().getId().equals(sourceProduct.getFamily().getId())) {
            throw new BadRequestException("Products must belong to the same family.");
        }

        Integer conversionFactor = targetProduct.getConversionFactor();
        if (sourceQuantity % conversionFactor != 0) {
            throw new BadRequestException("Source quantity must be a multiple of the conversion factor (" + conversionFactor + ").");
        }

        Integer targetQuantityAdded = sourceQuantity / conversionFactor;
        String conversionReference = "CONV-REV-" + sourceProductId + "-" + System.currentTimeMillis();

        InventoryManagementService.InventoryConsumption consumption = inventoryManagementService.consumeInventory(
                sourceProduct,
                sourceQuantity,
                com.example.perfume_budget.enums.InventoryReferenceType.CONVERSION,
                conversionReference,
                "product:" + sourceProductId,
                "Reverse conversion from " + sourceProduct.getName(),
                false
        );

        BigDecimal fromCostValue = consumption.totalCost();
        BigDecimal targetUnitCost = fromCostValue.divide(BigDecimal.valueOf(targetQuantityAdded), 2, RoundingMode.HALF_EVEN);
        inventoryManagementService.createConversionLayer(
                targetProduct,
                targetQuantityAdded,
                targetUnitCost,
                resolveReverseTargetSellingPrice(targetProduct),
                com.example.perfume_budget.enums.InventoryLayerSourceType.CONVERSION_IN,
                conversionReference,
                notes,
                LocalDateTime.now()
        );

        BigDecimal toCostValue = fromCostValue;
        BigDecimal variance = fromCostValue.subtract(toCostValue);

        User currentUser = authUserUtil.getCurrentUser();

        StockConversion conversion = StockConversion.builder()
                .conversionNumber(ProductCodeGenerator.generateConversionNumber("CONV-REV"))
                .fromProduct(sourceProduct)
                .toProduct(targetProduct)
                .fromQuantity(sourceQuantity)
                .toQuantity(targetQuantityAdded)
                .fromCostValue(fromCostValue)
                .toCostValue(toCostValue)
                .varianceAmount(variance)
                .direction(ConversionDirection.REVERSE)
                .convertedBy(currentUser)
                .notes(notes)
                .convertedAt(LocalDateTime.now())
                .build();

        StockConversion savedConversion = stockConversionRepository.save(conversion);
        bookkeepingService.recordStockConversion(savedConversion);

        return StockConversionMapper.toResponse(savedConversion);
    }

    private BigDecimal resolveForwardTargetSellingPrice(Product sourceProduct,
                                                        Product targetProduct,
                                                        BigDecimal sourceLayerSellingPrice,
                                                        Integer conversionFactor) {
        if (targetProduct.getPrice() != null && targetProduct.getPrice().getAmount() != null
                && targetProduct.getPrice().getAmount().compareTo(BigDecimal.ZERO) > 0) {
            return targetProduct.getPrice().getAmount();
        }
        if (sourceLayerSellingPrice != null) {
            return sourceLayerSellingPrice.divide(BigDecimal.valueOf(conversionFactor), 2, RoundingMode.HALF_EVEN);
        }
        return sourceProduct.getPrice().getAmount().divide(BigDecimal.valueOf(conversionFactor), 2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal resolveReverseTargetSellingPrice(Product targetProduct) {
        if (targetProduct.getPrice() != null && targetProduct.getPrice().getAmount() != null) {
            return targetProduct.getPrice().getAmount();
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
    }
}
