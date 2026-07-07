package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.request.ProductRequest;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.Category;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.model.UnitOfMeasure;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductFamilyRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UnitOfMeasureRepository;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.CloudinaryFileUploadUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductFamilyRepository productFamilyRepository;
    @Mock
    private UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private CloudinaryFileUploadUtil cloudinaryFileUploadUtil;
    @Mock
    private BookkeepingService bookkeepingService;
    @Mock
    private InventoryManagementService inventoryManagementService;
    @Mock
    private ProductCatalogCacheService productCatalogCacheService;
    @Mock
    private EffectivePriceService effectivePriceService;

    @InjectMocks
    private ProductServiceImpl productService;

    private Category category;
    private MultipartFile productImage;
    private ProductFamily family;
    private UnitOfMeasure eachUom;
    private UnitOfMeasure boxUom;
    private Product baseProduct;

    @BeforeEach
    void setUp() {
        category = Category.builder()
                .id(1L)
                .name("Fragrance")
                .description("All fragrances")
                .build();

        productImage = mock(MultipartFile.class);

        family = ProductFamily.builder()
                .id(5L)
                .familyCode("FAM-001")
                .name("Perfume A 50ML")
                .brand("Vestrex")
                .build();

        eachUom = UnitOfMeasure.builder().id(10L).code("EA").name("Each").build();
        boxUom = UnitOfMeasure.builder().id(11L).code("BOX").name("Box").build();

        baseProduct = Product.builder()
                .id(1L)
                .name("Perfume A")
                .slug("perfume-a")
                .description("Detailed description")
                .shortDescription("Short description")
                .sku("FAM-001-EA")
                .price(new Money(new BigDecimal("100.00"), CurrencyCode.USD))
                .costPrice(new Money(new BigDecimal("80.00"), CurrencyCode.USD))
                .stockQuantity(10)
                .soldCount(0)
                .lowStockThreshold(2)
                .isActive(true)
                .isEnlisted(true)
                .isFeatured(false)
                .imageUrl("http://image.com/a.jpg")
                .category(category)
                .family(family)
                .unitOfMeasure(eachUom)
                .isBaseUnit(true)
                .conversionFactor(1)
                .build();

        family.setBaseUnit(baseProduct);

        // By default no product/shop discount: effective price echoes the product's base price.
        lenient().when(effectivePriceService.activeShopDiscount()).thenReturn(Optional.empty());
        lenient().when(effectivePriceService.now()).thenReturn(java.time.LocalDateTime.now());
        lenient().when(effectivePriceService.compute(any(Product.class))).thenAnswer(this::echoEffectivePrice);
        lenient().when(effectivePriceService.compute(any(Product.class), any(), any())).thenAnswer(this::echoEffectivePrice);
    }

    private EffectivePrice echoEffectivePrice(org.mockito.invocation.InvocationOnMock invocation) {
        Product p = invocation.getArgument(0);
        BigDecimal amount = p.getPrice() != null && p.getPrice().getAmount() != null
                ? p.getPrice().getAmount() : BigDecimal.ZERO;
        CurrencyCode currency = p.getPrice() != null ? p.getPrice().getCurrencyCode() : null;
        return new EffectivePrice(amount, amount, currency, false, DiscountSource.NONE, null, BigDecimal.ZERO, null);
    }

    @Test
    void createProduct_NewFamily_SetsBaseUnitSkuAndPersistsInventory() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);

        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/new.jpg");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(unitOfMeasureRepository.findByCode("EA")).thenReturn(Optional.of(eachUom));
        when(productFamilyRepository.save(any(ProductFamily.class))).thenAnswer(invocation -> {
            ProductFamily savedFamily = invocation.getArgument(0);
            if (savedFamily.getId() == null) {
                savedFamily.setId(5L);
            }
            return savedFamily;
        });
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product savedProduct = invocation.getArgument(0);
            savedProduct.setId(1L);
            return savedProduct;
        });

        ProductDetails result = productService.createProduct(request);

        assertEquals("Perfume A", result.productName());
        ArgumentCaptor<Product> savedProductCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(savedProductCaptor.capture());
        Product savedProduct = savedProductCaptor.getValue();
        assertEquals("EA", savedProduct.getUnitOfMeasure().getCode());
        assertEquals("VES", savedProduct.getFamily().getFamilyCode().substring(0, 3));
        assertTrue(savedProduct.getSku().endsWith("-EA"));
        assertFalse(Boolean.TRUE.equals(savedProduct.getIsEnlisted()));
        // Stock is no longer seeded at creation; it enters via the receive endpoint.
        assertEquals(0, savedProduct.getStockQuantity());
        verifyNoInteractions(inventoryManagementService);
    }

    @Test
    void createProduct_ExistingFamilyVariant_CalculatesSkuAndCostFromBaseUnit() {
        ProductRequest request = buildRequest(false, 5L, "BOX", 12, new BigDecimal("80.00"), false);

        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/box.jpg");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productFamilyRepository.findById(5L)).thenReturn(Optional.of(family));
        when(unitOfMeasureRepository.findByCode("BOX")).thenReturn(Optional.of(boxUom));
        when(productRepository.existsBySku("FAM-001-BOX")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product savedProduct = invocation.getArgument(0);
            savedProduct.setId(2L);
            return savedProduct;
        });

        ProductDetails result = productService.createProduct(request);

        assertEquals("FAM-001-BOX", result.stockKeepingUnit());
        assertEquals("USD 960.00", result.costPrice());
        // Stock is no longer seeded at creation; it enters via the receive endpoint.
        assertEquals(0, result.stockQuantity());
        verifyNoInteractions(inventoryManagementService);
    }

    @Test
    void createProduct_CategoryNotFound_Throws() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productService.createProduct(request));
    }

    @Test
    void getProductListings_ReturnsMappedPage() {
        Pageable pageable = Pageable.unpaged();
        Page<Product> productPage = new PageImpl<>(List.of(baseProduct));
        when(productRepository.findAllByIsActiveTrueAndIsEnlistedTrue(pageable)).thenReturn(productPage);

        PageResponse<ProductListing> result = productService.getProductListings(pageable);

        assertEquals(1, result.content().size());
        assertEquals("Perfume A", result.content().get(0).productName());
    }

    @Test
    void searchProducts_ReturnsFilteredPage() {
        Pageable pageable = Pageable.unpaged();
        Page<Product> productPage = new PageImpl<>(List.of(baseProduct));
        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(productPage);

        PageResponse<ProductListing> result = productService.searchProducts(1L, "Perfume", pageable);

        assertEquals(1, result.content().size());
    }

    @Test
    void getAdminProductListings_ReturnsAllProductsIncludingInactive() {
        Pageable pageable = Pageable.unpaged();
        Product inactiveProduct = Product.builder()
                .id(2L)
                .name("Inactive Product")
                .slug("inactive-product")
                .price(new Money(new BigDecimal("50.00"), CurrencyCode.USD))
                .stockQuantity(3)
                .isActive(false)
                .isEnlisted(false)
                .build();
        Page<Product> productPage = new PageImpl<>(List.of(baseProduct, inactiveProduct));
        when(productRepository.findAll(pageable)).thenReturn(productPage);

        PageResponse<ProductListing> result = productService.getAdminProductListings(pageable);

        assertEquals(2, result.content().size());
    }

    @Test
    void searchAdminProducts_ReturnsProductsWithoutActiveFilter() {
        Pageable pageable = Pageable.unpaged();
        Product inactiveProduct = Product.builder()
                .id(2L)
                .name("Inactive Product")
                .sku("INACTIVE-001")
                .slug("inactive-product")
                .price(new Money(new BigDecimal("50.00"), CurrencyCode.USD))
                .stockQuantity(3)
                .isActive(false)
                .isEnlisted(false)
                .build();
        Page<Product> productPage = new PageImpl<>(List.of(inactiveProduct));
        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(productPage);

        PageResponse<ProductListing> result = productService.searchAdminProducts(1L, "Inactive", pageable);

        assertEquals(1, result.content().size());
        assertEquals("Inactive Product", result.content().getFirst().productName());
    }

    @Test
    void getProductDetailsPage_ForAdmin_DoesNotPublishViewEvent() {
        User admin = User.builder().roles(UserRole.ADMIN).build();
        when(authUserUtil.getCurrentUser()).thenReturn(admin);
        ProductDetailsPageResponse cachedResponse = ProductDetailsPageResponse.builder()
                .productId(1L)
                .slug("perfume-a")
                .productName("Perfume A")
                .productShortDescription("Short description")
                .productDescription("Detailed description")
                .productImageUrl("http://image.com/a.jpg")
                .category("Fragrance")
                .sellingPrice("USD 100.00")
                .isOutOfStock(false)
                .isFeatured(false)
                .build();
        when(productCatalogCacheService.getProductDetailsPage("perfume-a")).thenReturn(cachedResponse);

        ProductDetailsPageResponse result = productService.getProductDetailsPage("perfume-a");

        assertEquals("perfume-a", result.slug());
        verify(productCatalogCacheService).getProductDetailsPage("perfume-a");
        verify(eventPublisher, never()).publishEvent(any(ViewCountEvent.class));
    }

    @Test
    void getProductDetailsPage_ForCustomer_PublishesViewEventOnEachCall() {
        when(authUserUtil.getCurrentUser()).thenReturn(null);
        ProductDetailsPageResponse cachedResponse = ProductDetailsPageResponse.builder()
                .productId(1L)
                .slug("perfume-a")
                .productName("Perfume A")
                .productShortDescription("Short description")
                .productDescription("Detailed description")
                .productImageUrl("http://image.com/a.jpg")
                .category("Fragrance")
                .sellingPrice("USD 100.00")
                .isOutOfStock(false)
                .isFeatured(false)
                .build();
        when(productCatalogCacheService.getProductDetailsPage("perfume-a")).thenReturn(cachedResponse);

        ProductDetailsPageResponse first = productService.getProductDetailsPage("perfume-a");
        ProductDetailsPageResponse second = productService.getProductDetailsPage("perfume-a");

        assertEquals("perfume-a", first.slug());
        assertEquals("perfume-a", second.slug());
        verify(productCatalogCacheService, times(2)).getProductDetailsPage("perfume-a");
        verify(eventPublisher, times(2)).publishEvent(any(ViewCountEvent.class));
    }

    @Test
    void updateProduct_ChangingUomRegeneratesSku() {
        ProductRequest updateRequest = buildRequest(false, 5L, "BOX", 12, new BigDecimal("80.00"), true);
        when(productRepository.findById(1L)).thenReturn(Optional.of(baseProduct));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(unitOfMeasureRepository.findByCode("BOX")).thenReturn(Optional.of(boxUom));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductDetails result = productService.updateProduct(1L, updateRequest);

        assertEquals("FAM-001-BOX", result.stockKeepingUnit());
        verify(productRepository).save(baseProduct);
    }

    @Test
    void createProduct_AllowsWalkInOnlyProductWithoutImage() {
        ProductRequest request = new ProductRequest(
                "Walk In Perfume",
                "Detailed description",
                "Short description",
                "Vestrex",
                "50ML",
                CurrencyCode.USD,
                new BigDecimal("100.00"),
                new BigDecimal("80.00"),
                2,
                1L,
                null,
                null,
                null,
                true,
                true,
                false,
                false,
                null
        );

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(unitOfMeasureRepository.findByCode("EA")).thenReturn(Optional.of(eachUom));
        when(productFamilyRepository.save(any(ProductFamily.class))).thenAnswer(invocation -> {
            ProductFamily savedFamily = invocation.getArgument(0);
            if (savedFamily.getId() == null) {
                savedFamily.setId(5L);
            }
            return savedFamily;
        });
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product savedProduct = invocation.getArgument(0);
            savedProduct.setId(1L);
            return savedProduct;
        });

        ProductDetails result = productService.createProduct(request);

        assertNotNull(result);
        assertFalse(result.isEnlisted());
    }

    @Test
    void createProduct_ActiveAndEnlistedWithoutImage_Throws() {
        ProductRequest request = new ProductRequest(
                "Ecommerce Perfume",
                "Detailed description",
                "Short description",
                "Vestrex",
                "50ML",
                CurrencyCode.USD,
                new BigDecimal("100.00"),
                new BigDecimal("80.00"),
                2,
                1L,
                null,
                null,
                null,
                true,
                true,
                true,
                false,
                null
        );

        assertThrows(BadRequestException.class, () -> productService.createProduct(request));
    }

    @Test
    void getProductDetailsPage_UnenlistedProduct_Throws() {
        when(productCatalogCacheService.getProductDetailsPage("perfume-a"))
                .thenThrow(new ResourceNotFoundException("Product not Found."));

        assertThrows(ResourceNotFoundException.class, () -> productService.getProductDetailsPage("perfume-a"));
    }

    @Test
    void updateProduct_UpdatesEnlistedFlag() {
        ProductRequest updateRequest = buildRequest(false, 5L, "EA", 1, new BigDecimal("80.00"), false);
        when(productRepository.findById(1L)).thenReturn(Optional.of(baseProduct));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductDetails result = productService.updateProduct(1L, updateRequest);

        assertFalse(result.isEnlisted());
        verify(productRepository).save(baseProduct);
    }

    @Test
    void getFeaturedProducts_ReturnsMappedFeaturedProducts() {
        ProductListing featured = ProductListing.builder()
                .productId(2L)
                .productName("Featured Perfume")
                .slug("featured-perfume")
                .build();
        when(productCatalogCacheService.getFeaturedProducts())
                .thenReturn(List.of(featured));

        List<ProductListing> result = productService.getFeaturedProducts();

        assertEquals(1, result.size());
        assertEquals("Featured Perfume", result.get(0).productName());
        verify(productCatalogCacheService).getFeaturedProducts();
    }

    @Test
    void getFeaturedProducts_ReturnsEmptyList_WhenNoneExist() {
        when(productCatalogCacheService.getFeaturedProducts())
                .thenReturn(List.of());

        List<ProductListing> result = productService.getFeaturedProducts();

        assertTrue(result.isEmpty());
    }

    @Test
    void getFeaturedProducts_ReturnsAtMost8Products() {
        List<ProductListing> eightProducts = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(i -> ProductListing.builder()
                        .productId((long) i)
                        .productName("Featured " + i)
                        .slug("featured-" + i)
                        .build())
                .toList();
        when(productCatalogCacheService.getFeaturedProducts())
                .thenReturn(eightProducts);

        List<ProductListing> result = productService.getFeaturedProducts();

        assertEquals(8, result.size());
    }

    @Test
    void deleteProduct_DelegatesToRepository() {
        productService.deleteProduct(1L);
        verify(productRepository).deleteById(1L);
    }

    private ProductRequest buildRequest(
            boolean isNewProduct,
            Long familyId,
            String uomCode,
            Integer conversionFactor,
            BigDecimal costPrice,
            Boolean isEnlisted
    ) {
        return new ProductRequest(
                "Perfume A",
                "Detailed description",
                "Short description",
                "Vestrex",
                "50ML",
                CurrencyCode.USD,
                new BigDecimal("100.00"),
                costPrice,
                2,
                1L,
                familyId,
                uomCode,
                conversionFactor,
                isNewProduct,
                true,
                isEnlisted,
                false,
                productImage
        );
    }
}
