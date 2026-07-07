package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.product.response.AvailableUomsResponse;
import com.example.perfume_budget.dto.product.response.ProductFamilySummaryResponse;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.model.UnitOfMeasure;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.repository.ProductFamilyRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UnitOfMeasureRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductFamilyServiceImplTest {

    @Mock
    private ProductFamilyRepository familyRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private UnitOfMeasureRepository unitOfMeasureRepository;

    @InjectMocks
    private ProductFamilyServiceImpl productFamilyService;

    private ProductFamily family;
    private UnitOfMeasure eachUom;
    private UnitOfMeasure boxUom;
    private UnitOfMeasure packUom;

    @BeforeEach
    void setUp() {
        eachUom = UnitOfMeasure.builder().id(1L).code("EA").name("Each").build();
        boxUom = UnitOfMeasure.builder().id(2L).code("BOX").name("Box").build();
        packUom = UnitOfMeasure.builder().id(3L).code("PACK").name("Pack").build();

        Product baseUnit = Product.builder()
                .id(10L)
                .sku("FAM-001-EA")
                .costPrice(new Money(new BigDecimal("10.00"), CurrencyCode.USD))
                .unitOfMeasure(eachUom)
                .build();

        family = ProductFamily.builder()
                .id(100L)
                .familyCode("FAM-001")
                .name("Perfume A")
                .brand("Vestrex")
                .baseUnit(baseUnit)
                .build();
    }

    @Test
    void getAllFamilies_ReturnsMappedSummaries() {
        when(familyRepository.findAll()).thenReturn(List.of(family));

        List<ProductFamilySummaryResponse> result = productFamilyService.getAllFamilies();

        assertEquals(1, result.size());
        assertEquals("FAM-001", result.getFirst().familyCode());
        assertEquals("FAM-001-EA", result.getFirst().baseUnitSku());
    }

    @Test
    void getAvailableUoms_ExcludesTakenUoms() {
        Product takenVariant = Product.builder().unitOfMeasure(boxUom).build();
        when(familyRepository.findById(100L)).thenReturn(Optional.of(family));
        when(productRepository.findByFamily(family)).thenReturn(List.of(family.getBaseUnit(), takenVariant));
        when(unitOfMeasureRepository.findAll()).thenReturn(List.of(eachUom, boxUom, packUom));

        AvailableUomsResponse result = productFamilyService.getAvailableUoms(100L);

        assertEquals(new BigDecimal("10.00"), result.baseUnitCost());
        assertEquals(List.of("PACK"), result.availableUoms().stream().map(uom -> uom.code()).toList());
        assertEquals(2, result.takenUoms().size());
    }

    @Test
    void getAvailableUoms_WhenFamilyMissing_Throws() {
        when(familyRepository.findById(100L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productFamilyService.getAvailableUoms(100L));
    }
}
