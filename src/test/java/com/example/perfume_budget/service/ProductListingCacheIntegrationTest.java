package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.model.Category;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductFamilyRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.ShopWideDiscountRepository;
import com.example.perfume_budget.repository.UnitOfMeasureRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.service.interfaces.ProductService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.CloudinaryFileUploadUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class ProductListingCacheIntegrationTest {

    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;
    @MockitoBean
    private ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;
    @MockitoBean
    private ProductRepository productRepository;
    @MockitoBean
    private CategoryRepository categoryRepository;
    @MockitoBean
    private ProductFamilyRepository productFamilyRepository;
    @MockitoBean
    private UnitOfMeasureRepository unitOfMeasureRepository;
    @MockitoBean
    private AuthUserUtil authUserUtil;
    @MockitoBean
    private CloudinaryFileUploadUtil cloudinaryFileUploadUtil;
    @MockitoBean
    private BookkeepingService bookkeepingService;
    @MockitoBean
    private InventoryManagementService inventoryManagementService;
    @MockitoBean
    private ShopWideDiscountRepository shopWideDiscountRepository;

    @Autowired
    private ProductService productService;

    @TestConfiguration
    static class CacheTestConfig {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager("customerProductListings", "featuredProducts", "productDetailsPage");
        }
    }

    @Test
    void getProductListings_CachesRepeatedRequestsAndEvictsOnDelete() {
        Pageable pageable = PageRequest.of(0, 10);
        Product product = Product.builder()
                .id(1L)
                .name("Cached Perfume")
                .slug("cached-perfume")
                .shortDescription("Short")
                .price(new Money(new BigDecimal("100.00"), com.example.perfume_budget.enums.CurrencyCode.GHS))
                .stockQuantity(5)
                .isActive(true)
                .isEnlisted(true)
                .build();

        Page<Product> productPage = new PageImpl<>(List.of(product), pageable, 1);
        when(productRepository.findAllByIsActiveTrueAndIsEnlistedTrue(pageable)).thenReturn(productPage);

        PageResponse<ProductListing> first = productService.getProductListings(pageable);
        PageResponse<ProductListing> second = productService.getProductListings(pageable);

        assertEquals(1, first.content().size());
        assertEquals(1, second.content().size());
        verify(productRepository, times(1)).findAllByIsActiveTrueAndIsEnlistedTrue(pageable);

        productService.deleteProduct(1L);

        PageResponse<ProductListing> third = productService.getProductListings(pageable);
        assertEquals(1, third.content().size());
        verify(productRepository, times(2)).findAllByIsActiveTrueAndIsEnlistedTrue(pageable);
        verify(productRepository).deleteById(1L);
    }

    @Test
    void getFeaturedProducts_CachesRepeatedRequests() {
        Product product = Product.builder()
                .id(2L)
                .name("Featured Perfume")
                .slug("featured-perfume")
                .shortDescription("Short")
                .price(new Money(new BigDecimal("150.00"), CurrencyCode.GHS))
                .stockQuantity(5)
                .isActive(true)
                .isEnlisted(true)
                .isFeatured(true)
                .build();
        when(productRepository.findTop8ByIsActiveTrueAndIsEnlistedTrueAndIsFeaturedTrue())
                .thenReturn(List.of(product));

        List<ProductListing> first = productService.getFeaturedProducts();
        List<ProductListing> second = productService.getFeaturedProducts();

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        verify(productRepository, times(1)).findTop8ByIsActiveTrueAndIsEnlistedTrueAndIsFeaturedTrue();
    }

    @Test
    void getProductDetailsPage_CachesDataAndHitsRepositoryOnce() {
        Product product = Product.builder()
                .id(3L)
                .name("Detail Perfume")
                .slug("detail-perfume")
                .shortDescription("Short")
                .description("Detailed")
                .price(new Money(new BigDecimal("175.00"), CurrencyCode.GHS))
                .stockQuantity(9)
                .isActive(true)
                .isEnlisted(true)
                .build();
        when(productRepository.findBySlugAndIsActiveTrueAndIsEnlistedTrue("detail-perfume"))
                .thenReturn(java.util.Optional.of(product));

        ProductDetailsPageResponse first = productService.getProductDetailsPage("detail-perfume");
        ProductDetailsPageResponse second = productService.getProductDetailsPage("detail-perfume");

        assertEquals("detail-perfume", first.slug());
        assertEquals("detail-perfume", second.slug());
        verify(productRepository, times(1)).findBySlugAndIsActiveTrueAndIsEnlistedTrue("detail-perfume");
    }
}
