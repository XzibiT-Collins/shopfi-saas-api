package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.category.request.CategoryRequest;
import com.example.perfume_budget.dto.category.response.CategoryResponse;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Category;
import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.service.interfaces.CategoryService;
import com.example.perfume_budget.utils.SlugGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {
    private final CategoryRepository categoryRepository;
    private static final String CATEGORY_NOT_FOUND = "Category not found";
    private static final String INVALID_CATEGORY_ID = "Invalid request, please provide a category ID.";

    @Transactional
    @Override
    public CategoryResponse createCategory(CategoryRequest request) {
        checkIfCategoryNameExists(request.categoryName());
        Category newCategory = Category.builder()
                .name(request.categoryName().trim().toUpperCase())
                .description(request.description())
                .slug(SlugGenerator.generateNormalSlug(request.categoryName()))
                .build();

        newCategory = categoryRepository.save(newCategory);
        return CategoryResponse.builder()
                .categoryId(newCategory.getId())
                .categoryName(newCategory.getName())
                .description(newCategory.getDescription())
                .slug(newCategory.getSlug())
                .build();
    }

    @Override
    public CategoryResponse getCategoryById(Long categoryId) {
        if(categoryId == null){
            throw new BadRequestException(INVALID_CATEGORY_ID);
        }
        Category category = categoryRepository.findById(categoryId).orElseThrow(()-> new ResourceNotFoundException(CATEGORY_NOT_FOUND));
        return CategoryResponse.builder()
                .categoryId(category.getId())
                .categoryName(category.getName())
                .description(category.getDescription())
                .slug(category.getSlug())
                .build();
    }

    @Override
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().stream().map(category -> CategoryResponse.builder()
                .categoryId(category.getId())
                .categoryName(category.getName())
                .description(category.getDescription())
                .slug(category.getSlug())
                .build()).toList();
    }

    @Transactional
    @Override
    public CategoryResponse updateCategory(Long categoryId, CategoryRequest request) {
        Category categoryToUpdate = categoryRepository.findById(categoryId).orElseThrow(()-> new ResourceNotFoundException(CATEGORY_NOT_FOUND));

        boolean changed = false;

        changed |= updateCategoryName(categoryToUpdate, request);
        changed |= updateCategoryDescription(categoryToUpdate, request);

        categoryToUpdate = changed ? categoryRepository.save(categoryToUpdate) : Category.builder()
                .id(categoryId)
                .name(request.categoryName())
                .description(request.description())
                .slug(categoryToUpdate.getSlug())
                .build();
        return CategoryResponse.builder()
                .categoryId(categoryToUpdate.getId())
                .categoryName(categoryToUpdate.getName())
                .description(categoryToUpdate.getDescription())
                .slug(categoryToUpdate.getSlug())
                .build();
    }

    @Override
    public void deleteCategory(Long categoryId) {
        categoryRepository.deleteById(categoryId);
    }

    // HELPER METHODS
    private void checkIfCategoryNameExists(String categoryName) {
        if(categoryRepository.existsByName(categoryName.trim().toUpperCase())){
            throw new DuplicateResourceException("Category already exists.");
        }
    }

    private boolean updateCategoryDescription(Category categoryToUpdate, CategoryRequest request) {
        if(Objects.equals(categoryToUpdate.getDescription(), request.description())) return false;
        categoryToUpdate.setDescription(request.description());
        return true;
    }

    private boolean updateCategoryName(Category categoryToUpdate, CategoryRequest request) {
        if(Objects.equals(categoryToUpdate.getName(), request.categoryName())) return false;
        categoryToUpdate.setName(request.categoryName().trim().toUpperCase());
        categoryToUpdate.setSlug(SlugGenerator.generateNormalSlug(request.categoryName()));
        return true;
    }
}
