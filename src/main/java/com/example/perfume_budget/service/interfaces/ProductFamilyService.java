package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.product.response.AvailableUomsResponse;
import com.example.perfume_budget.dto.product.response.ProductFamilySummaryResponse;

import java.util.List;

public interface ProductFamilyService {
    List<ProductFamilySummaryResponse> getAllFamilies();
    AvailableUomsResponse getAvailableUoms(Long familyId);
}
