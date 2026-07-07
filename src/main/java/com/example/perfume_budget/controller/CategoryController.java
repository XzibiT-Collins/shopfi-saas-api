package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.category.request.CategoryRequest;
import com.example.perfume_budget.dto.category.response.CategoryResponse;
import com.example.perfume_budget.service.interfaces.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/category")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/add-category")
    public ResponseEntity<CustomApiResponse<CategoryResponse>> addCategory(@Valid @RequestBody CategoryRequest request){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(categoryService.createCategory(request))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/update/{categoryId}")
    public ResponseEntity<CustomApiResponse<CategoryResponse>> updateCategory(@PathVariable Long categoryId, @Valid @RequestBody CategoryRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(categoryService.updateCategory(categoryId, request)));
    }

    @GetMapping("/all")
    public ResponseEntity<CustomApiResponse<List<CategoryResponse>>> getCategories(){
        return ResponseEntity.ok().body(CustomApiResponse.success(categoryService.getAllCategories()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{categoryId}")
    public ResponseEntity<CustomApiResponse<CategoryResponse>> getCategoryById(@PathVariable Long categoryId){
        return ResponseEntity.ok().body(CustomApiResponse.success(categoryService.getCategoryById(categoryId)));
    }

    @DeleteMapping("/delete/{categoryId}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long categoryId){
        categoryService.deleteCategory(categoryId);
        return ResponseEntity.noContent().build();
    }
}
