package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.category.request.CategoryRequest;
import com.example.perfume_budget.dto.category.response.CategoryResponse;

import java.util.List;

public interface CategoryService {
    CategoryResponse createCategory(CategoryRequest request);
    CategoryResponse getCategoryById(Long categoryId);
    List<CategoryResponse> getAllCategories();
    CategoryResponse updateCategory(Long categoryId, CategoryRequest request);
    void deleteCategory(Long categoryId);
}
