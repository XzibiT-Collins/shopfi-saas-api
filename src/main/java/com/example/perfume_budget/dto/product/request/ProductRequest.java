package com.example.perfume_budget.dto.product.request;

import com.example.perfume_budget.enums.CurrencyCode;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank(message = "Product name is required") String productName,
        String productDescription,
        String shortDescription,
        @Nullable String brand,
        @Nullable String size,
        @NotNull CurrencyCode currency,
        @NotNull BigDecimal sellingPrice,
        @NotNull BigDecimal costPrice,
        Integer lowStockThreshold,
        @Nullable Long categoryId,
        @Nullable Long familyId,
        @Nullable String uomCode, // Admin selects "EA", "BOX", etc.
        @Nullable Integer conversionFactor,
        Boolean isNewProduct, // Toggle: New Product (true) or Add Variant (false)
        Boolean isActive,
        Boolean isEnlisted,
        Boolean isFeatured,
        @Nullable MultipartFile productImage
) {}
