package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.category.request.CategoryRequest;
import com.example.perfume_budget.dto.category.response.CategoryResponse;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Category;
import com.example.perfume_budget.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private Category testCategory;
    private CategoryRequest categoryRequest;

    @BeforeEach
    void setUp() {
        testCategory = Category.builder()
                .id(1L)
                .name("FRAGRANCE")
                .description("All fragrances")
                .slug("fragrance")
                .build();

        categoryRequest = new CategoryRequest("Fragrance", "All fragrances");
    }

    @Test
    void createCategory_Success() {
        when(categoryRepository.existsByName("FRAGRANCE")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

        CategoryResponse result = categoryService.createCategory(categoryRequest);

        assertNotNull(result);
        assertEquals("FRAGRANCE", result.categoryName());
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    void createCategory_Failure_AlreadyExists() {
        when(categoryRepository.existsByName("FRAGRANCE")).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> categoryService.createCategory(categoryRequest));
    }

    @Test
    void getCategoryById_Success() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));

        CategoryResponse result = categoryService.getCategoryById(1L);

        assertNotNull(result);
        assertEquals(1L, result.categoryId());
    }

    @Test
    void getCategoryById_Failure_NullId() {
        assertThrows(BadRequestException.class, () -> categoryService.getCategoryById(null));
    }

    @Test
    void getCategoryById_Failure_NotFound() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> categoryService.getCategoryById(1L));
    }

    @Test
    void getAllCategories_Success() {
        when(categoryRepository.findAll()).thenReturn(List.of(testCategory));

        List<CategoryResponse> result = categoryService.getAllCategories();

        assertEquals(1, result.size());
    }

    @Test
    void updateCategory_Success_NameChanged() {
        CategoryRequest updateRequest = new CategoryRequest("New Name", "All fragrances");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

        CategoryResponse result = categoryService.updateCategory(1L, updateRequest);

        assertNotNull(result);
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    void updateCategory_Success_NoChanges() {
        // Correcting the request to match testCategory exactly (Name is FRAGRANCE in testCategory)
        CategoryRequest sameRequest = new CategoryRequest("FRAGRANCE", "All fragrances");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));

        categoryService.updateCategory(1L, sameRequest);

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void deleteCategory_Success() {
        categoryService.deleteCategory(1L);
        verify(categoryRepository).deleteById(1L);
    }
}
