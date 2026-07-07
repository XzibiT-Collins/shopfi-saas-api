package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.mapper.ProductMapper;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCatalogCacheService {
    private final ProductRepository productRepository;
    private final EffectivePriceService effectivePriceService;

    @Cacheable(cacheNames = "featuredProducts")
    public List<ProductListing> getFeaturedProducts() {
        log.info("Cache miss for featured products, querying database...");
        ShopWideDiscount shop = effectivePriceService.activeShopDiscount().orElse(null);
        var now = effectivePriceService.now();
        return productRepository.findTop8ByIsActiveTrueAndIsEnlistedTrueAndIsFeaturedTrue()
                .stream()
                .map(product -> ProductMapper.toProductListing(product, effectivePriceService.compute(product, shop, now)))
                .toList();
    }

    @Cacheable(cacheNames = "productDetailsPage", key = "#slug")
    public ProductDetailsPageResponse getProductDetailsPage(String slug) {
        log.info("Cache miss for product details page, querying database...");
        Product product = productRepository.findBySlugAndIsActiveTrueAndIsEnlistedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Product not Found."));

        EffectivePrice effectivePrice = effectivePriceService.compute(product);
        return ProductDetailsPageResponse.builder()
                .productId(product.getId())
                .productName(product.getName())
                .productShortDescription(product.getShortDescription())
                .productDescription(product.getDescription())
                .productImageUrl(product.getImageUrl())
                .category(product.getCategory() != null ? product.getCategory().getName() : "")
                .sellingPrice(effectivePrice.effectiveDisplay())
                .originalPrice(effectivePrice.originalDisplay())
                .onSale(effectivePrice.onSale())
                .discountPercentage(effectivePrice.discountPercentage())
                .discountEndsAt(effectivePrice.endsAt())
                .isOutOfStock(product.getStockQuantity() <= 0)
                .isFeatured(Boolean.TRUE.equals(product.getIsFeatured()))
                .slug(product.getSlug())
                .build();
    }
}
