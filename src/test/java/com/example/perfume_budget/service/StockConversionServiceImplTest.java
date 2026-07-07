package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.product.response.ProductVariantSummaryResponse;
import com.example.perfume_budget.dto.product.response.StockConversionResponse;
import com.example.perfume_budget.enums.ConversionDirection;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.model.StockConversion;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.StockConversionRepository;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.utils.AuthUserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockConversionServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockConversionRepository stockConversionRepository;
    @Mock
    private BookkeepingService bookkeepingService;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private InventoryManagementService inventoryManagementService;
    @Mock
    private FrontDeskAccessService frontDeskAccessService;

    @InjectMocks
    private StockConversionServiceImpl stockConversionService;

    private Product boxProduct;
    private Product eaProduct;
    private User adminUser;

    @BeforeEach
    void setUp() {
        adminUser = User.builder().id(1L).fullName("Admin User").build();
        ProductFamily family = ProductFamily.builder()
                .id(1L)
                .familyCode("FAM-001")
                .name("Test Family")
                .build();

        eaProduct = Product.builder()
                .id(1L)
                .name("EA Product")
                .sku("FAM-001-EA")
                .costPrice(new Money(new BigDecimal("10.00"), CurrencyCode.GHS))
                .stockQuantity(10)
                .isBaseUnit(true)
                .conversionFactor(1)
                .family(family)
                .build();

        family.setBaseUnit(eaProduct);

        boxProduct = Product.builder()
                .id(2L)
                .name("BOX Product")
                .sku("FAM-001-BOX")
                .costPrice(new Money(new BigDecimal("120.00"), CurrencyCode.GHS))
                .stockQuantity(5)
                .isBaseUnit(false)
                .conversionFactor(12)
                .family(family)
                .build();
    }

    @Test
    void convertForward_Success() {
        when(productRepository.findById(2L)).thenReturn(Optional.of(boxProduct));
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(stockConversionRepository.save(any(StockConversion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryManagementService.consumeInventory(eq(boxProduct), eq(1), any(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(new InventoryManagementService.InventoryConsumption(
                        List.of(new InventoryManagementService.InventoryConsumptionLine(1L, 1, new BigDecimal("120.00"), new BigDecimal("180.00"))),
                        new BigDecimal("120.00")
                ));
        when(inventoryManagementService.createConversionLayer(any(Product.class), anyInt(), any(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StockConversionResponse result = stockConversionService.convertForward(2L, 1, "Testing forward");

        assertNotNull(result);
        assertEquals(BigDecimal.ZERO.setScale(4), result.varianceAmount().setScale(4));
        assertEquals(ConversionDirection.FORWARD, result.direction());
        verify(bookkeepingService).recordStockConversion(any(StockConversion.class));
    }

    @Test
    void getReverseConversionTargetVariants_ReturnsFamilyVariantsExcludingSource() {
        Product packProduct = Product.builder()
                .id(3L)
                .name("PACK Product")
                .sku("FAM-001-PACK")
                .stockQuantity(2)
                .costPrice(new Money(new BigDecimal("60.00"), CurrencyCode.GHS))
                .family(eaProduct.getFamily())
                .isBaseUnit(false)
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(eaProduct));
        when(productRepository.findByFamily(eaProduct.getFamily())).thenReturn(List.of(eaProduct, boxProduct, packProduct));

        List<ProductVariantSummaryResponse> result = stockConversionService.getReverseConversionTargetVariants(1L);

        assertEquals(2, result.size());
        assertEquals(List.of(2L, 3L), result.stream().map(ProductVariantSummaryResponse::variantId).toList());
        assertEquals(List.of("FAM-001-BOX", "FAM-001-PACK"), result.stream().map(ProductVariantSummaryResponse::variantSku).toList());
        assertEquals(List.of("BOX Product", "PACK Product"), result.stream().map(ProductVariantSummaryResponse::variantName).toList());
    }

    @Test
    void getReverseConversionTargetVariants_WhenSourceIsNotBaseUnit_Throws() {
        when(productRepository.findById(2L)).thenReturn(Optional.of(boxProduct));

        assertThrows(BadRequestException.class, () -> stockConversionService.getReverseConversionTargetVariants(2L));
    }

    @Test
    void convertForward_InsufficientStock() {
        when(productRepository.findById(2L)).thenReturn(Optional.of(boxProduct));
        boxProduct.setStockQuantity(0);

        assertThrows(BadRequestException.class, () -> stockConversionService.convertForward(2L, 1, "No stock"));
    }

    @Test
    void convertReverse_Success_NoVariance() {
        eaProduct.setStockQuantity(24);
        when(productRepository.findById(1L)).thenReturn(Optional.of(eaProduct));
        when(productRepository.findById(2L)).thenReturn(Optional.of(boxProduct));
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(stockConversionRepository.save(any(StockConversion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryManagementService.consumeInventory(eq(eaProduct), eq(12), any(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(new InventoryManagementService.InventoryConsumption(
                        List.of(new InventoryManagementService.InventoryConsumptionLine(1L, 12, new BigDecimal("10.00"), new BigDecimal("100.00"))),
                        new BigDecimal("120.00")
                ));
        when(inventoryManagementService.createConversionLayer(any(Product.class), anyInt(), any(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StockConversionResponse result = stockConversionService.convertReverse(1L, 12, 2L, "Testing reverse");

        assertNotNull(result);
        assertEquals(BigDecimal.ZERO.setScale(4), result.varianceAmount().setScale(4));
        verify(bookkeepingService).recordStockConversion(any(StockConversion.class));
    }

    @Test
    void convertReverse_Success_UsesConsumedLayerCost() {
        eaProduct.setStockQuantity(24);

        when(productRepository.findById(1L)).thenReturn(Optional.of(eaProduct));
        when(productRepository.findById(2L)).thenReturn(Optional.of(boxProduct));
        when(authUserUtil.getCurrentUser()).thenReturn(adminUser);
        when(stockConversionRepository.save(any(StockConversion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryManagementService.consumeInventory(eq(eaProduct), eq(12), any(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(new InventoryManagementService.InventoryConsumption(
                        List.of(
                                new InventoryManagementService.InventoryConsumptionLine(1L, 10, new BigDecimal("10.00"), new BigDecimal("100.00")),
                                new InventoryManagementService.InventoryConsumptionLine(2L, 2, new BigDecimal("12.00"), new BigDecimal("100.00"))
                        ),
                        new BigDecimal("124.00")
                ));
        when(inventoryManagementService.createConversionLayer(any(Product.class), anyInt(), any(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StockConversionResponse result = stockConversionService.convertReverse(1L, 12, 2L, "Testing variance");

        assertEquals(new BigDecimal("124.0000"), result.fromCostValue().setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), result.varianceAmount().setScale(4));
        verify(bookkeepingService).recordStockConversion(any(StockConversion.class));
    }

    @Test
    void convertReverse_MissingTargetProductId_Throws() {
        assertThrows(BadRequestException.class, () -> stockConversionService.convertReverse(1L, 12, null, "Missing target"));
    }

    @Test
    void convertReverse_InvalidQuantity_Throws() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(eaProduct));
        when(productRepository.findById(2L)).thenReturn(Optional.of(boxProduct));

        assertThrows(BadRequestException.class, () -> stockConversionService.convertReverse(1L, 10, 2L, "Invalid qty"));
    }
}
