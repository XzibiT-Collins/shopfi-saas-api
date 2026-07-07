package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.product.response.AvailableUomsResponse;
import com.example.perfume_budget.dto.product.response.ProductFamilySummaryResponse;
import com.example.perfume_budget.dto.product.response.UnitOfMeasureResponse;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.ProductFamilyMapper;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.repository.ProductFamilyRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UnitOfMeasureRepository;
import com.example.perfume_budget.service.interfaces.ProductFamilyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductFamilyServiceImpl implements ProductFamilyService {
    private final ProductFamilyRepository familyRepository;
    private final ProductRepository productRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;

    @Override
    public List<ProductFamilySummaryResponse> getAllFamilies() {
        return familyRepository.findAll().stream()
                .map(ProductFamilyMapper::toSummary)
                .toList();
    }

    @Override
    public AvailableUomsResponse getAvailableUoms(Long familyId) {
        ProductFamily family = familyRepository.findById(familyId)
                .orElseThrow(() -> new ResourceNotFoundException("Product family not found."));

        Set<String> takenUoms = productRepository.findByFamily(family).stream()
                .map(product -> product.getUnitOfMeasure().getCode())
                .collect(Collectors.toSet());

        List<UnitOfMeasureResponse> availableUoms = unitOfMeasureRepository.findAll().stream()
                .filter(unitOfMeasure -> !takenUoms.contains(unitOfMeasure.getCode()))
                .map(ProductFamilyMapper::toResponse)
                .toList();

        BigDecimal baseUnitCost = family.getBaseUnit() != null && family.getBaseUnit().getCostPrice() != null
                ? family.getBaseUnit().getCostPrice().getAmount()
                : BigDecimal.ZERO;

        return new AvailableUomsResponse(
                family.getFamilyCode(),
                baseUnitCost,
                availableUoms,
                takenUoms
        );
    }
}
