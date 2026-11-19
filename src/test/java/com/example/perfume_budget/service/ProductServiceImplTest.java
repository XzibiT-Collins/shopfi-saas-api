package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.request.ProductRequest;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.dto.product.response.PublicProductListing;
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
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.mockito.Spy
    private ProductViewRateLimiter viewRateLimiter = new ProductViewRateLimiter(1000, 1000);
    @org.mockito.Spy
    private ProductViewDeduper viewDeduper = new ProductViewDeduper(java.time.Clock.systemUTC(), 30, 1000);

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
    void createProduct_NewFamily_DrawsAnotherSuffixWhenTheFamilyCodeIsTaken() {
        // ORD-02b: the family code is <prefix>-<3 random digits>; a code already in use (unique family_code / sku) is
        // redrawn instead of failing the create (ConcurrentTenantLoadTest flake: same prefix, same suffix -> 409)
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/new.jpg");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(unitOfMeasureRepository.findByCode("EA")).thenReturn(Optional.of(eachUom));
        List<String> checked = new java.util.ArrayList<>();
        when(productFamilyRepository.existsByFamilyCode(any())).thenAnswer(i -> {
            checked.add(i.getArgument(0));
            return checked.size() < 3; // the first two draws are taken
        });
        when(productFamilyRepository.save(any(ProductFamily.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productService.createProduct(request);

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals(3, checked.size());
        assertEquals(checked.get(2), saved.getValue().getFamily().getFamilyCode(), "the free code is the one used");
        assertEquals(checked.get(2) + "-EA", saved.getValue().getSku());
    }

    // ---------------------------------------------------------------- ORD-02c: family code allocation

    private static final java.util.regex.Pattern THREE_DIGITS = java.util.regex.Pattern.compile("VES-\\d{3}-50ML");
    private static final java.util.regex.Pattern FOUR_DIGITS = java.util.regex.Pattern.compile("VES-\\d{4}-50ML");

    private void stubNewFamilyCreate() {
        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/new.jpg");
        lenient().when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        lenient().when(unitOfMeasureRepository.findByCode("EA")).thenReturn(Optional.of(eachUom));
        lenient().when(productFamilyRepository.save(any(ProductFamily.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static org.springframework.dao.DataIntegrityViolationException uniqueViolation(String constraint) {
        return new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("duplicate key",
                        new java.sql.SQLException("duplicate key value violates unique constraint", "23505"), constraint));
    }

    private static void assertFamilyCodeUnavailable(Throwable thrown) {
        com.example.perfume_budget.exception.ApiCodeException e =
                assertInstanceOf(com.example.perfume_budget.exception.ApiCodeException.class, thrown);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getStatus());
        assertEquals("FAMILY_CODE_UNAVAILABLE", e.getCode());
        assertEquals("Could not allocate a product code for this name/brand; try again or change the name", e.getMessage());
    }

    @Test
    void createProduct_NewFamily_FallsBackToAFourDigitSuffixAfterTenTakenThreeDigitDraws() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        stubNewFamilyCreate();
        List<String> checked = new java.util.ArrayList<>();
        when(productFamilyRepository.existsByFamilyCode(any())).thenAnswer(i -> {
            checked.add(i.getArgument(0));
            return THREE_DIGITS.matcher(i.getArgument(0)).matches(); // every 3-digit code is taken
        });

        productService.createProduct(request);

        assertEquals(11, checked.size());
        assertTrue(checked.subList(0, 10).stream().allMatch(c -> THREE_DIGITS.matcher(c).matches()), checked.toString());
        assertTrue(FOUR_DIGITS.matcher(checked.get(10)).matches(), checked.toString());
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals(checked.get(10), saved.getValue().getFamily().getFamilyCode());
        assertEquals(checked.get(10) + "-EA", saved.getValue().getSku());
    }

    @Test
    void createProduct_NewFamily_TwentyTakenDrawsAnswer409FamilyCodeUnavailableAndWriteNothing() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        stubNewFamilyCreate();
        List<String> checked = new java.util.ArrayList<>();
        when(productFamilyRepository.existsByFamilyCode(any())).thenAnswer(i -> checked.add(i.getArgument(0)));

        Throwable thrown = assertThrows(RuntimeException.class, () -> productService.createProduct(request));

        assertFamilyCodeUnavailable(thrown);
        assertEquals(20, checked.size());
        assertEquals(10, checked.stream().filter(c -> THREE_DIGITS.matcher(c).matches()).count());
        assertEquals(10, checked.stream().filter(c -> FOUR_DIGITS.matcher(c).matches()).count());
        verify(productFamilyRepository, never()).save(any());
        verify(productRepository, never()).save(any());
    }

    @Test
    void createProduct_NewFamily_ACodeWhoseBaseSkuIsAlreadyUsedIsRedrawn() {
        // a customised / legacy SKU <code>-EA on a product of another family would hit uc_products_sku
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        stubNewFamilyCreate();
        when(productFamilyRepository.existsByFamilyCode(any())).thenReturn(false);
        List<String> skus = new java.util.ArrayList<>();
        when(productRepository.existsBySku(any())).thenAnswer(i -> {
            skus.add(i.getArgument(0));
            return skus.size() < 3;
        });

        productService.createProduct(request);

        assertEquals(3, skus.size());
        assertTrue(skus.stream().allMatch(s -> s.endsWith("-50ML-EA")), skus.toString());
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals(skus.get(2), saved.getValue().getSku());
    }

    @Test
    void createProduct_NewFamily_AConcurrentFamilyCodeClashIsRetriedOnceInAFreshTransaction() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        stubNewFamilyCreate();
        when(productFamilyRepository.existsByFamilyCode(any())).thenReturn(false);
        List<String> inserted = new java.util.ArrayList<>();
        when(productFamilyRepository.save(any(ProductFamily.class))).thenAnswer(invocation -> {
            ProductFamily f = invocation.getArgument(0);
            if (f.getId() == null && inserted.isEmpty()) {
                inserted.add(f.getFamilyCode());
                throw uniqueViolation("uc_product_families_family_code");
            }
            f.setId(9L);
            return f;
        });

        ProductDetails result = productService.createProduct(request);

        assertEquals("Perfume A", result.productName());
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
        verify(cloudinaryFileUploadUtil, times(1)).uploadProductImage(productImage);
        verify(productRepository, times(1)).save(any(Product.class));
    }

    @Test
    void createProduct_NewFamily_AConcurrentBaseSkuClashIsRetriedOnce() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        stubNewFamilyCreate();
        when(productFamilyRepository.existsByFamilyCode(any())).thenReturn(false);
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            if (saves.incrementAndGet() == 1) throw uniqueViolation("uc_products_sku");
            return invocation.getArgument(0);
        });

        productService.createProduct(request);

        assertEquals(2, saves.get());
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    void createProduct_NewFamily_ASecondConcurrentClashAnswers409FamilyCodeUnavailable() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        stubNewFamilyCreate();
        when(productFamilyRepository.existsByFamilyCode(any())).thenReturn(false);
        when(productFamilyRepository.save(any(ProductFamily.class))).thenThrow(uniqueViolation("uc_product_families_family_code"));

        Throwable thrown = assertThrows(RuntimeException.class, () -> productService.createProduct(request));

        assertFamilyCodeUnavailable(thrown);
        verify(productFamilyRepository, times(2)).save(any(ProductFamily.class));
        verify(transactionManager, times(2)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void createProduct_NewFamily_AnyOtherConstraintViolationIsNotRetriedAndKeepsItsGeneric409() {
        ProductRequest request = buildRequest(true, null, null, null, new BigDecimal("80.00"), false);
        stubNewFamilyCreate();
        when(productFamilyRepository.existsByFamilyCode(any())).thenReturn(false);
        org.springframework.dao.DataIntegrityViolationException other = uniqueViolation("uc_products_slug");
        when(productRepository.save(any(Product.class))).thenThrow(other);

        Throwable thrown = assertThrows(RuntimeException.class, () -> productService.createProduct(request));

        assertSame(other, thrown);
        verify(productRepository, times(1)).save(any(Product.class));
    }

    @Test
    void createProduct_Variant_ASkuClashIsNotRetried_andAnswersTheDocumented400() {
        ProductRequest request = buildRequest(false, 5L, "BOX", 6, null, false);
        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/new.jpg");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productFamilyRepository.findById(5L)).thenReturn(Optional.of(family));
        when(unitOfMeasureRepository.findByCode("BOX")).thenReturn(Optional.of(boxUom));
        org.springframework.dao.DataIntegrityViolationException clash = uniqueViolation("uc_products_sku");
        when(productRepository.save(any(Product.class))).thenThrow(clash);

        Throwable thrown = assertThrows(RuntimeException.class, () -> productService.createProduct(request));

        // ORD-02d: the loser of two identical concurrent variant adds gets the documented 400, not the generic 409
        assertInstanceOf(com.example.perfume_budget.exception.BadRequestException.class, thrown);
        assertEquals("Variant with UOM BOX already exists for this family.", thrown.getMessage());
        verify(productRepository, times(1)).save(any(Product.class));
    }

    @Test
    void createProduct_Variant_AFamilyDeletedByAConcurrentDeleteAnswers404_not500() {
        // the base unit proxy of the family is gone when the variant first reads it (the delete committed in between)
        ProductRequest request = buildRequest(false, 5L, "BOX", 6, null, false);
        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/new.jpg");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productFamilyRepository.findById(5L)).thenReturn(Optional.of(family));
        when(unitOfMeasureRepository.findByCode("BOX")).thenReturn(Optional.of(boxUom));
        Product vanishedBase = spy(baseProduct);
        doThrow(new jakarta.persistence.EntityNotFoundException("gone")).when(vanishedBase).getCostPrice();
        family.setBaseUnit(vanishedBase);

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, () -> productService.createProduct(request));

        assertEquals("Product family not found.", e.getMessage());
        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /** FIX-G (FIX-B low): only the family base unit case is mapped; any other vanished reference keeps its own error. */
    @Test
    void createProduct_Variant_AnotherVanishedReferenceIsNotReportedAsAMissingFamily() {
        ProductRequest request = buildRequest(false, 5L, "BOX", 6, null, false);
        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/new.jpg");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productFamilyRepository.findById(5L)).thenReturn(Optional.of(family));
        when(unitOfMeasureRepository.findByCode("BOX")).thenReturn(Optional.of(boxUom));
        family.setBaseUnit(baseProduct);
        when(productRepository.save(any(Product.class))).thenThrow(new jakarta.persistence.EntityNotFoundException("category 9 gone"));

        jakarta.persistence.EntityNotFoundException e = assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> productService.createProduct(request));

        assertEquals("category 9 gone", e.getMessage());
    }

    @Test
    void createProduct_Variant_AnyOtherConstraintViolationKeepsItsGeneric409Path() {
        ProductRequest request = buildRequest(false, 5L, "BOX", 6, null, false);
        when(cloudinaryFileUploadUtil.uploadProductImage(productImage)).thenReturn("http://image.com/new.jpg");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productFamilyRepository.findById(5L)).thenReturn(Optional.of(family));
        when(unitOfMeasureRepository.findByCode("BOX")).thenReturn(Optional.of(boxUom));
        org.springframework.dao.DataIntegrityViolationException other = uniqueViolation("uc_products_slug");
        when(productRepository.save(any(Product.class))).thenThrow(other);

        assertSame(other, assertThrows(RuntimeException.class, () -> productService.createProduct(request)));
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

        PageResponse<PublicProductListing> result = productService.getProductListings(pageable);

        assertEquals(1, result.content().size());
        assertEquals("Perfume A", result.content().get(0).productName());
    }

    @Test
    void searchProducts_ReturnsFilteredPage() {
        Pageable pageable = Pageable.unpaged();
        Page<Product> productPage = new PageImpl<>(List.of(baseProduct));
        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(productPage);

        PageResponse<PublicProductListing> result = productService.searchProducts(1L, "Perfume", pageable);

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

    private static ProductDetailsPageResponse page() {
        return ProductDetailsPageResponse.builder().productId(1L).slug("perfume-a").productName("Perfume A")
                .productShortDescription("Short description").productDescription("Detailed description")
                .productImageUrl("http://image.com/a.jpg").category("Fragrance").sellingPrice("USD 100.00")
                .isOutOfStock(false).isFeatured(false).build();
    }

    @Test
    void getProductDetailsPage_isReadOnly_neverPublishesAViewEvent() {
        when(productCatalogCacheService.getProductDetailsPage("perfume-a")).thenReturn(page());

        productService.getProductDetailsPage("perfume-a");
        productService.getProductDetailsPage("perfume-a");

        verify(productCatalogCacheService, times(2)).getProductDetailsPage("perfume-a");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void recordView_countsOncePerTenantProductAndClient() {
        com.example.perfume_budget.tenancy.TenantContext.set(com.example.perfume_budget.tenancy.TenantIdentity.tenant("acme", "tenant_acme"));
        try {
            when(authUserUtil.getCurrentUser()).thenReturn(null);
            when(productCatalogCacheService.getProductDetailsPage("perfume-a")).thenReturn(page());

            assertTrue(productService.recordView("perfume-a", "1.2.3.4", false));
            assertFalse(productService.recordView("perfume-a", "1.2.3.4", false), "repeat inside the window");
            assertTrue(productService.recordView("perfume-a", "5.6.7.8", false), "another client");
            verify(eventPublisher, times(2)).publishEvent(any(ViewCountEvent.class));
        } finally {
            com.example.perfume_budget.tenancy.TenantContext.clear();
        }
    }

    @Test
    void recordView_doesNotCountBotsAdminsUnknownOrMalformedSlugs() {
        com.example.perfume_budget.tenancy.TenantContext.set(com.example.perfume_budget.tenancy.TenantIdentity.tenant("acme", "tenant_acme"));
        try {
            when(productCatalogCacheService.getProductDetailsPage("perfume-a")).thenReturn(page());
            when(productCatalogCacheService.getProductDetailsPage("gone")).thenThrow(new ResourceNotFoundException("Product not Found."));

            assertFalse(productService.recordView("perfume-a", "9.9.9.1", true), "automated");
            assertFalse(productService.recordView("gone", "9.9.9.2", false), "unknown or unlisted");
            assertFalse(productService.recordView("Bad Slug", "9.9.9.3", false), "malformed");
            when(authUserUtil.getCurrentUser()).thenReturn(User.builder().roles(UserRole.ADMIN).build());
            assertFalse(productService.recordView("perfume-a", "9.9.9.4", false), "admin session");
            verify(productCatalogCacheService, never()).getProductDetailsPage("Bad Slug");
            verifyNoInteractions(eventPublisher);
            // an admin did not use up the dedupe slot of its address: a visitor behind it still counts
            when(authUserUtil.getCurrentUser()).thenReturn(null);
            assertTrue(productService.recordView("perfume-a", "9.9.9.4", false));
        } finally {
            com.example.perfume_budget.tenancy.TenantContext.clear();
        }
    }

    @Test
    void recordView_withoutATenantCountsNothing() {
        assertFalse(productService.recordView("perfume-a", "7.7.7.7", false));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void getPublicProductsByIds_emptyInputDoesNotQuery() {
        assertTrue(productService.getPublicProductsByIds(List.of()).isEmpty());
        verifyNoInteractions(productRepository);
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
        PublicProductListing featured = PublicProductListing.builder()
                .productId(2L)
                .productName("Featured Perfume")
                .slug("featured-perfume")
                .build();
        when(productCatalogCacheService.getFeaturedProducts())
                .thenReturn(List.of(featured));

        List<PublicProductListing> result = productService.getFeaturedProducts();

        assertEquals(1, result.size());
        assertEquals("Featured Perfume", result.get(0).productName());
        verify(productCatalogCacheService).getFeaturedProducts();
    }

    @Test
    void getFeaturedProducts_ReturnsEmptyList_WhenNoneExist() {
        when(productCatalogCacheService.getFeaturedProducts())
                .thenReturn(List.of());

        List<PublicProductListing> result = productService.getFeaturedProducts();

        assertTrue(result.isEmpty());
    }

    @Test
    void getFeaturedProducts_ReturnsAtMost8Products() {
        List<PublicProductListing> eightProducts = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(i -> PublicProductListing.builder()
                        .productId((long) i)
                        .productName("Featured " + i)
                        .slug("featured-" + i)
                        .build())
                .toList();
        when(productCatalogCacheService.getFeaturedProducts())
                .thenReturn(eightProducts);

        List<PublicProductListing> result = productService.getFeaturedProducts();

        assertEquals(8, result.size());
    }

    @Test
    void deleteProduct_plainVariantIsDeleted() {
        Product variant = Product.builder().id(2L).family(family).build();
        family.setBaseUnit(baseProduct);
        when(productRepository.findById(2L)).thenReturn(Optional.of(variant));
        productService.deleteProduct(2L);
        verify(productRepository).delete(variant);
        verify(productFamilyRepository, never()).delete(any(ProductFamily.class));
    }

    @Test
    void deleteProduct_unknownIdIsANoOp() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());
        productService.deleteProduct(99L);
        verify(productRepository, never()).delete(any(Product.class));
    }

    @Test
    void deleteProduct_baseUnitAloneInItsFamilyTakesTheFamilyWithIt() {
        baseProduct.setFamily(family);
        family.setBaseUnit(baseProduct);
        when(productRepository.findById(1L)).thenReturn(Optional.of(baseProduct));
        when(productRepository.countByFamilyAndIdNot(family, 1L)).thenReturn(0L);
        productService.deleteProduct(1L);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(productFamilyRepository, productRepository);
        order.verify(productFamilyRepository).saveAndFlush(family);
        order.verify(productRepository).delete(baseProduct);
        order.verify(productFamilyRepository).delete(family);
        assertNull(family.getBaseUnit());
    }

    @Test
    void deleteProduct_baseUnitWithVariantsIs409FamilyHasVariants() {
        baseProduct.setFamily(family);
        family.setBaseUnit(baseProduct);
        when(productRepository.findById(1L)).thenReturn(Optional.of(baseProduct));
        when(productRepository.countByFamilyAndIdNot(family, 1L)).thenReturn(2L);
        com.example.perfume_budget.exception.ApiCodeException e = assertThrows(
                com.example.perfume_budget.exception.ApiCodeException.class, () -> productService.deleteProduct(1L));
        assertEquals("FAMILY_HAS_VARIANTS", e.getCode());
        verify(productRepository, never()).delete(any(Product.class));
        verify(productFamilyRepository, never()).delete(any(ProductFamily.class));
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
