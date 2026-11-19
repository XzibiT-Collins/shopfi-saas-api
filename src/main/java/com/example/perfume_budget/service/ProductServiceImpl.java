package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.request.ProductDiscountRequest;
import com.example.perfume_budget.dto.product.request.ProductRequest;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.dto.product.response.PublicProductListing;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.mapper.ProductMapper;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductFamilyRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.repository.UnitOfMeasureRepository;
import com.example.perfume_budget.service.interfaces.ProductService;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.specification.ProductSpecification;
import com.example.perfume_budget.utils.AuthUserUtil;
import com.example.perfume_budget.utils.CloudinaryFileUploadUtil;
import com.example.perfume_budget.utils.DbErrorDetails;
import com.example.perfume_budget.utils.PaginationUtil;
import com.example.perfume_budget.utils.ProductCodeGenerator;
import com.example.perfume_budget.utils.SlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductFamilyRepository productFamilyRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final ApplicationEventPublisher eventPublisher;
    private static final String PRODUCT_NOT_FOUND = "Product not Found.";
    private static final String CATEGORY_NOT_FOUND = "Selected category does not exist.";
    private final AuthUserUtil authUserUtil;
    private final CloudinaryFileUploadUtil cloudinaryFileUploadUtil;
    private final BookkeepingService bookkeepingService;
    private final ProductCatalogCacheService productCatalogCacheService;
    private final EffectivePriceService effectivePriceService;
    private final PlatformTransactionManager transactionManager;
    private final ProductViewRateLimiter viewRateLimiter;
    private final ProductViewDeduper viewDeduper;

    /**
     * ORD-02c: not one big transaction any more. The image is uploaded once, then the rows are written in a
     * transaction of their own, so a family-code clash with a concurrent create (check-then-insert race on the
     * family_code / base SKU unique constraints) can be retried once in a fresh transaction. A second clash answers
     * 409 FAMILY_CODE_UNAVAILABLE. Only the new-family path retries; adding a variant keeps its answers.
     */
    @Override
    @CacheEvict(cacheNames = {CatalogCaches.PUBLIC_LISTINGS, CatalogCaches.PUBLIC_FEATURED, CatalogCaches.PRODUCT_DETAILS}, allEntries = true)
    public ProductDetails createProduct(ProductRequest request) {
        checkIfImageIsPresentForEcommerceProduct(request);

        String uploadedImageUrl = uploadToCloudIfImageIsPresent(request.productImage());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        if (!Boolean.TRUE.equals(request.isNewProduct())) {
            try {
                return tx.execute(status -> createProductRows(request, uploadedImageUrl));
            } catch (DataIntegrityViolationException e) {
                // ORD-02d: two identical concurrent variant adds both pass the existsBySku check; the loser's insert
                // hits uc_products_sku and gets the documented answer, not the generic 409 constraint text
                if (isSkuClash(e)) {
                    throw new BadRequestException(variantExistsMessage(request.uomCode() != null ? request.uomCode() : "EA"));
                }
                throw e;
            }
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> createProductRows(request, uploadedImageUrl));
            } catch (DataIntegrityViolationException e) {
                if (!isFamilyCodeClash(e)) {
                    throw e;
                }
                DbErrorDetails details = DbErrorDetails.of(e);
                if (attempt >= FAMILY_CODE_TRANSACTION_ATTEMPTS) {
                    log.warn("Family code taken by a concurrent create again ({}): answering {}", details, FAMILY_CODE_UNAVAILABLE);
                    throw familyCodeUnavailable();
                }
                log.info("Family code taken by a concurrent create ({}): retrying once", details);
            }
        }
    }

    private ProductDetails createProductRows(ProductRequest request, String uploadedImageUrl) {
        String size = request.size() != null ? " " + request.size() : "";
        String name = request.productName() + size;

        Product newProduct = new Product();
        newProduct.setName(request.productName());
        newProduct.setBrand(request.brand());
        newProduct.setSize(request.size());
        newProduct.setDescription(request.productDescription());
        newProduct.setShortDescription(request.shortDescription());
        newProduct.setSlug(SlugGenerator.generateSlug(name));
        newProduct.setImageUrl(uploadedImageUrl);
        newProduct.setPrice(new Money(request.sellingPrice(), request.currency()));
        newProduct.setStockQuantity(0);
        newProduct.setLowStockThreshold(request.lowStockThreshold() != null ? request.lowStockThreshold() : 5);
        newProduct.setIsActive(request.isActive() != null && request.isActive());
        newProduct.setIsEnlisted(request.isEnlisted() != null && request.isEnlisted());
        newProduct.setIsFeatured(request.isFeatured() != null && request.isFeatured());

        setProductCategory(request.categoryId(), newProduct);

        return createNewProduct(newProduct, request, size);
    }

    /** ORD-02b/ORD-02c: draws per suffix width before moving on (3 digits, then 4 digits). */
    static final int FAMILY_CODE_ATTEMPTS = 10;
    /** ORD-02c (PO decision 2026-11-18): the 3-digit suffix first, then the 4-digit fallback. */
    static final int[] FAMILY_CODE_SUFFIX_DIGITS = {3, 4};
    /** ORD-02c: the first transaction plus one retry after a concurrent unique violation on the family code / base SKU. */
    static final int FAMILY_CODE_TRANSACTION_ATTEMPTS = 2;
    public static final String FAMILY_CODE_UNAVAILABLE = "FAMILY_CODE_UNAVAILABLE";
    public static final String FAMILY_CODE_UNAVAILABLE_MESSAGE =
            "Could not allocate a product code for this name/brand; try again or change the name";
    /** The unique constraints a new family's rows can clash on (V1: product_families.family_code, products.sku). */
    static final Set<String> FAMILY_CODE_CONSTRAINTS = Set.of("uc_product_families_family_code", "uc_products_sku");

    private static ApiCodeException familyCodeUnavailable() {
        return new ApiCodeException(HttpStatus.CONFLICT, FAMILY_CODE_UNAVAILABLE, FAMILY_CODE_UNAVAILABLE_MESSAGE);
    }

    static String variantExistsMessage(String uomCode) {
        return "Variant with UOM " + uomCode + " already exists for this family.";
    }

    /** A unique violation (SQLState 23505) on products.sku: a concurrent create took the same SKU. */
    static boolean isSkuClash(DataIntegrityViolationException e) {
        DbErrorDetails details = DbErrorDetails.of(e);
        String constraint = details.constraint();
        return "23505".equals(details.sqlState()) && constraint != null
                && "uc_products_sku".equals(constraint.substring(constraint.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
    }

    /** A unique violation (SQLState 23505) on the family code or the SKU: a concurrent create took the same code. */
    static boolean isFamilyCodeClash(DataIntegrityViolationException e) {
        DbErrorDetails details = DbErrorDetails.of(e);
        String constraint = details.constraint();
        if (!"23505".equals(details.sqlState()) || constraint == null) {
            return false;
        }
        String name = constraint.substring(constraint.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return FAMILY_CODE_CONSTRAINTS.contains(name);
    }

    /**
     * ORD-02b/ORD-02c: the family code is a 3-letter prefix plus random digits, so families with the same prefix (same
     * brand, or names like "X Item1", "X Item2") collide by chance. A code is free when no family has it and no product
     * already holds its base SKU {@code <code>-EA} (a legacy / customised SKU would hit uc_products_sku). Up to 10 draws
     * with a 3-digit suffix, then up to 10 with a 4-digit suffix; existing codes are never changed. When all 20 are taken:
     * 409 FAMILY_CODE_UNAVAILABLE. A concurrent create can still take the code between this check and the insert; the
     * caller retries that once.
     */
    private String unusedFamilyCode(ProductRequest request) {
        for (int digits : FAMILY_CODE_SUFFIX_DIGITS) {
            for (int attempt = 0; attempt < FAMILY_CODE_ATTEMPTS; attempt++) {
                String code = ProductCodeGenerator.generateFamilyCode(request.productName(), request.brand(), request.size(), digits);
                if (!productFamilyRepository.existsByFamilyCode(code) && !productRepository.existsBySku(code + "-EA")) {
                    return code;
                }
            }
        }
        log.warn("No free family code after {} draws per suffix width {}: answering {}", FAMILY_CODE_ATTEMPTS,
                java.util.Arrays.toString(FAMILY_CODE_SUFFIX_DIGITS), FAMILY_CODE_UNAVAILABLE);
        throw familyCodeUnavailable();
    }

    private ProductDetails createNewProduct(Product newProduct, ProductRequest request, String size) {
        ProductFamily family;
        UnitOfMeasure uom;
        if (Boolean.TRUE.equals(request.isNewProduct())) {
            // SCENARIO 1: New Product (Creates new family + base EA variant)
            String familyCode = unusedFamilyCode(request);
            family = ProductFamily.builder()
                    .familyCode(familyCode)
                    .name(request.productName() + size)
                    .brand(request.brand())
                    .build();
            family = productFamilyRepository.save(family);

            uom = unitOfMeasureRepository.findByCode("EA")
                    .orElseThrow(() -> new ResourceNotFoundException("Base UOM 'EA' not found. Ensure it is seeded."));

            newProduct.setFamily(family);
            newProduct.setUnitOfMeasure(uom);
            newProduct.setConversionFactor(1);
            newProduct.setIsBaseUnit(true);
            newProduct.setCostPrice(new Money(request.costPrice(), request.currency()));
            newProduct.setSku(family.getFamilyCode() + "-EA");

            Product savedProduct = productRepository.save(newProduct);
            family.setBaseUnit(savedProduct);
            productFamilyRepository.save(family);

            return toDetails(savedProduct);

        } else {
            // SCENARIO 2: Add Variant to Existing Family
            family = productFamilyRepository.findById(request.familyId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product family not found."));

            String uomCode = request.uomCode() != null ? request.uomCode() : "EA";
            uom = unitOfMeasureRepository.findByCode(uomCode)
                    .orElseThrow(() -> new ResourceNotFoundException("UOM not found: " + uomCode));

            // Check if variant already exists
            String generatedSku = family.getFamilyCode() + "-" + uom.getCode();
            if (productRepository.existsBySku(generatedSku)) {
                throw new BadRequestException(variantExistsMessage(uom.getCode()));
            }

            Product baseUnit = family.getBaseUnit();
            if (baseUnit == null) {
                throw new BadRequestException("Selected family does not have a base unit (EA) assigned.");
            }

            int convFactor = request.conversionFactor() != null ? request.conversionFactor() : 1;
            // Fixed conversion factors for specific UOMs
            if ("DOZEN".equals(uom.getCode())) convFactor = 12;
            if ("PAIR".equals(uom.getCode())) convFactor = 2;

            newProduct.setFamily(family);
            newProduct.setUnitOfMeasure(uom);
            newProduct.setConversionFactor(convFactor);
            newProduct.setIsBaseUnit(false);
            newProduct.setSku(generatedSku);

            // Auto-calculate cost price = base unit cost * conversion factor
            Money baseCost = baseUnitCost(baseUnit);
            BigDecimal calculatedCost = baseCost.getAmount().multiply(BigDecimal.valueOf(convFactor));
            newProduct.setCostPrice(new Money(calculatedCost, baseCost.getCurrencyCode()));

            Product savedProduct = productRepository.save(newProduct);
            return toDetails(savedProduct);
        }
    }

    /**
     * FIX-B/FIX-G: the family's base unit is a lazy proxy; if a concurrent product delete removed the family (and its
     * base unit) after this request read the family, the first use of the proxy throws EntityNotFoundException. Only
     * that case is the documented 404; any other vanished reference keeps its own error.
     */
    private static Money baseUnitCost(Product baseUnit) {
        try {
            return baseUnit.getCostPrice();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            throw new ResourceNotFoundException("Product family not found.");
        }
    }

    private void setProductCategory(Long categoryId, Product newProduct) {
        if (categoryId != null) {
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new ResourceNotFoundException(CATEGORY_NOT_FOUND));
            newProduct.setCategory(category);
        }
    }

    private String uploadToCloudIfImageIsPresent(MultipartFile multipartFile) {
        if (multipartFile != null) {
            return cloudinaryFileUploadUtil.uploadProductImage(multipartFile);
        }else{
            return null;
        }
    }

    private void checkIfImageIsPresentForEcommerceProduct(ProductRequest request) {
        if (Boolean.TRUE.equals(request.isActive())
                && Boolean.TRUE.equals(request.isEnlisted())
                && request.productImage() == null) {
            throw new BadRequestException("Product image is required when product is active and enlisted for ecommerce.");
        }
    }

    /** Cache names of the public (anonymous) catalog. NEW names (SEC-03b): entries written before the public DTO existed
     *  live under the old names, are never read again and expire by TTL; a rollback to the old build is safe. */
    static final String PUBLIC_LISTINGS_CACHE = CatalogCaches.PUBLIC_LISTINGS;
    static final String PUBLIC_FEATURED_CACHE = CatalogCaches.PUBLIC_FEATURED;

    /** Public sort properties: the storefront's own sorts (createdAt, name, price.amount) and the id. Anything else is a
     *  probe for hidden values (stock, cost, sku, counters, flags) and answers 400 INVALID_SORT. */
    static final java.util.Set<String> PUBLIC_SORT_PROPERTIES = java.util.Set.of("id", "name", "createdAt", "price.amount");

    /** Optional: absent in plain unit tests (no caching then). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.cache.CacheManager cacheManager;

    static void requirePublicSort(Pageable pageable) {
        for (org.springframework.data.domain.Sort.Order o : pageable.getSort()) {
            if (!PUBLIC_SORT_PROPERTIES.contains(o.getProperty())) {
                throw new com.example.perfume_budget.exception.ApiCodeException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "INVALID_SORT", "Unsupported sort property.");
            }
        }
    }

    private static boolean allPublic(java.util.Collection<?> items) {
        return items.stream().allMatch(PublicProductListing.class::isInstance);
    }

    @Override
    public PageResponse<PublicProductListing> getProductListings(Pageable pageable) {
        requirePublicSort(pageable);
        String key = pageable.isPaged() ? pageable.getPageNumber() + ":" + pageable.getPageSize() + ":" + pageable.getSort() : null;
        org.springframework.cache.Cache cache = (cacheManager == null || key == null) ? null : cacheManager.getCache(PUBLIC_LISTINGS_CACHE);
        if (cache != null) {
            org.springframework.cache.Cache.ValueWrapper hit = cache.get(key);
            if (hit != null) {
                // Defensive: only a page made of PublicProductListing may be served from the shared cache; anything else
                // (an old-shape entry, a foreign writer) is a miss and is evicted.
                if (hit.get() instanceof PageResponse<?> page && allPublic(page.content())) {
                    @SuppressWarnings("unchecked")
                    PageResponse<PublicProductListing> typed = (PageResponse<PublicProductListing>) page;
                    return typed;
                }
                log.warn("Ignoring a non-public cached listing page under {}", PUBLIC_LISTINGS_CACHE);
                cache.evict(key);
            }
        }
        log.info("Cache miss: {}", PUBLIC_LISTINGS_CACHE);
        Page<PublicProductListing> productListings = productRepository.findAllByIsActiveTrueAndIsEnlistedTrue(pageable)
                .map(publicListingMapper());
        PageResponse<PublicProductListing> fresh = PaginationUtil.createPageResponse(productListings);
        if (cache != null) {
            cache.put(key, fresh);
        }
        return fresh;
    }

    @Override
    public PageResponse<PublicProductListing> searchProducts(Long categoryId, String searchTerm, Pageable pageable) {
        requirePublicSort(pageable);
        Specification<Product> spec = ProductSpecification.filterProducts(categoryId, searchTerm);
        Page<PublicProductListing> productListings = productRepository.findAll(spec, pageable).map(publicListingMapper());
        return PaginationUtil.createPageResponse(productListings);
    }

    @Override
    public PageResponse<ProductListing> getAdminProductListings(Pageable pageable) {
        Page<ProductListing> productListings = productRepository.findAll(pageable).map(listingMapper());
        return PaginationUtil.createPageResponse(productListings);
    }

    @Override
    public List<PublicProductListing> getFeaturedProducts(boolean inStockOnly) {
        if (!inStockOnly) {
            return getFeaturedProducts();
        }
        List<PublicProductListing> shelf = productCatalogCacheService.getInStockFeaturedProducts();
        if (!allPublic(shelf)) {
            log.warn("Ignoring a non-public cached in-stock featured list under {}", PUBLIC_FEATURED_CACHE);
            if (cacheManager != null && cacheManager.getCache(PUBLIC_FEATURED_CACHE) != null) {
                cacheManager.getCache(PUBLIC_FEATURED_CACHE).clear();
            }
            shelf = productCatalogCacheService.getInStockFeaturedProducts();
            if (!allPublic(shelf)) {
                throw new IllegalStateException("featured cache returned a non-public shape");
            }
        }
        return shelf;
    }

    @Override
    public List<PublicProductListing> getFeaturedProducts() {
        List<?> cached = productCatalogCacheService.getFeaturedProducts();
        if (!allPublic(cached)) {
            // an old-shape entry in the shared cache: drop it and rebuild (never serve it)
            log.warn("Ignoring a non-public cached featured list under {}", PUBLIC_FEATURED_CACHE);
            if (cacheManager != null && cacheManager.getCache(PUBLIC_FEATURED_CACHE) != null) {
                cacheManager.getCache(PUBLIC_FEATURED_CACHE).clear();
            }
            cached = productCatalogCacheService.getFeaturedProducts();
            if (!allPublic(cached)) {
                throw new IllegalStateException("featured cache returned a non-public shape");
            }
        }
        @SuppressWarnings("unchecked")
        List<PublicProductListing> typed = (List<PublicProductListing>) cached;
        return typed;
    }

    @Override
    public PageResponse<ProductListing> searchAdminProducts(Long categoryId, String searchTerm, Pageable pageable) {
        Specification<Product> spec = ProductSpecification.filterProductsForAdmin(categoryId, searchTerm);
        Page<ProductListing> productListings = productRepository.findAll(spec, pageable).map(listingMapper());
        return PaginationUtil.createPageResponse(productListings);
    }

    @Override
    public ProductDetails getProductDetails(Long productId) {
        log.info("Product details requested");
        // CAT-01: the category is fetched with the product (the mapper reads it after the session closed)
        Product product = productRepository.findWithCategoryById(productId).orElseThrow(() -> new ResourceNotFoundException(PRODUCT_NOT_FOUND));
        return toDetails(product);
    }

    @Override
    public ProductDetailsPageResponse getProductDetailsPage(String slug) {
        return productCatalogCacheService.getProductDetailsPage(slug);
    }

    @Override
    public List<PublicProductListing> getPublicProductsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Function<Product, PublicProductListing> mapper = publicListingMapper();
        java.util.Map<Long, Product> byId = new java.util.HashMap<>();
        for (Product p : productRepository.findAllByIdInAndIsActiveTrueAndIsEnlistedTrue(ids)) {
            byId.put(p.getId(), p);
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).map(mapper).toList();
    }

    @Override
    public boolean recordView(String slug, String clientKey, boolean automated) {
        viewRateLimiter.check(clientKey);
        if (automated || !ProductViewRequests.isWellFormedSlug(slug)) {
            return false;
        }
        String tenant = TenantContext.getSlug();
        if (tenant == null) {
            return false;
        }
        Long productId;
        try {
            productId = productCatalogCacheService.getProductDetailsPage(slug).productId();
        } catch (ResourceNotFoundException e) {
            return false;
        }
        if (productId == null || isAdminSession() || !viewDeduper.firstView(tenant, productId, clientKey)) {
            return false;
        }
        eventPublisher.publishEvent(ViewCountEvent.builder().productId(productId).build());
        return true;
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {CatalogCaches.PUBLIC_LISTINGS, CatalogCaches.PUBLIC_FEATURED, CatalogCaches.PRODUCT_DETAILS}, allEntries = true)
    public ProductDetails updateProduct(Long productId, ProductRequest request) {
        Product productToUpdate = productRepository.findById(productId).orElseThrow(() -> new ResourceNotFoundException(PRODUCT_NOT_FOUND));
        checkIfImageIsPresentForEcommerceProductUpdate(request, productToUpdate);
        Product updatedProduct = checkAndUpdateProductFields(productToUpdate, request);
        return toDetails(updatedProduct);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {CatalogCaches.PUBLIC_LISTINGS, CatalogCaches.PUBLIC_FEATURED, CatalogCaches.PRODUCT_DETAILS}, allEntries = true)
    public ProductDetails setProductDiscount(Long productId, ProductDiscountRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(PRODUCT_NOT_FOUND));
        validateProductDiscount(product, request);
        product.setDiscountType(request.discountType());
        product.setDiscountValue(request.discountValue());
        product.setDiscountStartAt(request.startAt());
        product.setDiscountEndAt(request.endAt());
        return toDetails(productRepository.save(product));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {CatalogCaches.PUBLIC_LISTINGS, CatalogCaches.PUBLIC_FEATURED, CatalogCaches.PRODUCT_DETAILS}, allEntries = true)
    public ProductDetails clearProductDiscount(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(PRODUCT_NOT_FOUND));
        product.setDiscountType(null);
        product.setDiscountValue(null);
        product.setDiscountStartAt(null);
        product.setDiscountEndAt(null);
        return toDetails(productRepository.save(product));
    }

    private void validateProductDiscount(Product product, ProductDiscountRequest request) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw new BadRequestException("Discount end date must be after the start date.");
        }
        if (request.discountType() == DiscountType.PERCENTAGE
                && request.discountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BadRequestException("Percentage discount cannot exceed 100.");
        }
        if (request.discountType() == DiscountType.FLAT) {
            BigDecimal price = product.getPrice() != null && product.getPrice().getAmount() != null
                    ? product.getPrice().getAmount() : BigDecimal.ZERO;
            if (request.discountValue().compareTo(price) >= 0) {
                throw new BadRequestException("Flat discount must be less than the product price.");
            }
        }
    }

    private Function<Product, PublicProductListing> publicListingMapper() {
        ShopWideDiscount shop = effectivePriceService.activeShopDiscount().orElse(null);
        LocalDateTime now = effectivePriceService.now();
        return product -> ProductMapper.toPublicProductListing(product, effectivePriceService.compute(product, shop, now));
    }

    private Function<Product, ProductListing> listingMapper() {
        ShopWideDiscount shop = effectivePriceService.activeShopDiscount().orElse(null);
        LocalDateTime now = effectivePriceService.now();
        return product -> ProductMapper.toProductListing(product, effectivePriceService.compute(product, shop, now));
    }

    private ProductDetails toDetails(Product product) {
        EffectivePrice effectivePrice = effectivePriceService.compute(product);
        return ProductMapper.toProductDetails(product, effectivePrice);
    }

    private void checkIfImageIsPresentForEcommerceProductUpdate(ProductRequest request, Product productToUpdate) {
        boolean willBeActive = request.isActive() != null ? request.isActive() : Boolean.TRUE.equals(productToUpdate.getIsActive());
        boolean willBeEnlisted = request.isEnlisted() != null ? request.isEnlisted() : Boolean.TRUE.equals(productToUpdate.getIsEnlisted());
        if(request.productImage() == null
                && (productToUpdate.getImageUrl()==null || productToUpdate.getImageUrl().equals(""))
                && willBeActive
                && willBeEnlisted){
            throw new BadRequestException("Product image is required when product is active and enlisted for ecommerce.");
        }
    }

    private boolean isAdminSession() {
        User currentUser = authUserUtil.getCurrentUser();
        return currentUser != null && UserRole.ADMIN.equals(currentUser.getRoles());
    }

    private Product checkAndUpdateProductFields(Product productToUpdate, ProductRequest request) {
        boolean changed = false;
        changed |= updateNameAndSlug(productToUpdate, request);
        changed |= updateDescriptions(productToUpdate, request);
        changed |= updateFamilyAndUom(productToUpdate, request);
        
        if (productToUpdate.getFamily() != null && productToUpdate.getUnitOfMeasure() != null) {
            String autoSku = productToUpdate.getFamily().getFamilyCode() + "-" + productToUpdate.getUnitOfMeasure().getCode();
            if (!Objects.equals(productToUpdate.getSku(), autoSku)) {
                productToUpdate.setSku(autoSku);
                changed = true;
            }
        }
        
        validateInventoryManagedFields(productToUpdate, request);
        changed |= updatePrices(productToUpdate, request);
        changed |= updateLowStockThreshold(productToUpdate, request);
        changed |= updateCategory(productToUpdate, request);
        changed |= updateFlags(productToUpdate, request);
        changed |= updateImage(productToUpdate, request);

        return changed ? productRepository.save(productToUpdate) : productToUpdate;
    }

    private boolean updateFamilyAndUom(Product product, ProductRequest request) {
        boolean changed = false;

        if (request.familyId() != null) {
            Long currentFamilyId = product.getFamily() != null ? product.getFamily().getId() : null;
            if (!Objects.equals(currentFamilyId, request.familyId())) {
                ProductFamily family = productFamilyRepository.findById(request.familyId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product family not found."));
                product.setFamily(family);
                changed = true;
            }
        }

        if (request.uomCode() != null) {
            String currentUomCode = product.getUnitOfMeasure() != null ? product.getUnitOfMeasure().getCode() : null;
            if (!Objects.equals(currentUomCode, request.uomCode())) {
                UnitOfMeasure uom = unitOfMeasureRepository.findByCode(request.uomCode())
                        .orElseThrow(() -> new ResourceNotFoundException("Unit of measure not found."));
                product.setUnitOfMeasure(uom);
                changed = true;
            }
        }

        if (request.conversionFactor() != null) {
            changed |= updateIfChanged(product::getConversionFactor, product::setConversionFactor, request.conversionFactor());
        }

        return changed;
    }

    private void validateInventoryManagedFields(Product product, ProductRequest request) {
        Money incomingCostPrice = new Money(request.costPrice(), request.currency());
        if (isMoneyDifferent(product.getCostPrice(), incomingCostPrice)) {
            throw new BadRequestException("Cost price must be updated through inventory management.");
        }

        Money incomingSellingPrice = new Money(request.sellingPrice(), request.currency());
        if (product.getStockQuantity() != null
                && product.getStockQuantity() > 0
                && isMoneyDifferent(product.getPrice(), incomingSellingPrice)) {
            throw new BadRequestException("Selling price for stocked products must be updated through inventory management.");
        }
    }

    private boolean updateNameAndSlug(Product product, ProductRequest request) {
        if (Objects.equals(product.getName(), request.productName())) return false;
        product.setName(request.productName());
        product.setSlug(SlugGenerator.generateSlug(request.productName()));
        return true;
    }

    private boolean updateDescriptions(Product product, ProductRequest request) {
        boolean changed = false;
        changed |= updateIfChanged(product::getDescription, product::setDescription, request.productDescription());
        changed |= updateIfChanged(product::getShortDescription, product::setShortDescription, request.shortDescription());
        return changed;
    }

    private boolean updatePrices(Product product, ProductRequest request) {
        boolean changed = false;

        Money newSellingPrice = new Money(request.sellingPrice(), request.currency());
        if (isMoneyDifferent(product.getPrice(), newSellingPrice)) {
            product.setPrice(newSellingPrice);
            changed = true;
        }

        return changed;
    }

    private boolean updateLowStockThreshold(Product product, ProductRequest request) {
        if (request.lowStockThreshold() == null) return false;
        return updateIfChanged(product::getLowStockThreshold, product::setLowStockThreshold, request.lowStockThreshold());
    }

    private boolean updateCategory(Product product, ProductRequest request) {
        Long requestedCategoryId = request.categoryId();

        // Remove category when client sends null (or an "empty" value like 0)
        if (requestedCategoryId == null || requestedCategoryId <= 0) {
            if (product.getCategory() == null) return false;
            product.setCategory(null);
            return true;
        }

        Category category = categoryRepository.findById(requestedCategoryId)
                .orElseThrow(() -> new ResourceNotFoundException(CATEGORY_NOT_FOUND));

        Long currentCategoryId = product.getCategory() != null ? product.getCategory().getId() : null;
        if (Objects.equals(currentCategoryId, category.getId())) return false;

        product.setCategory(category);
        return true;
    }

    private boolean updateFlags(Product product, ProductRequest request) {
        boolean changed = false;

        if (request.isActive() != null) {
            changed |= updateIfChanged(product::getIsActive, product::setIsActive, request.isActive());
        }
        if (request.isEnlisted() != null) {
            changed |= updateIfChanged(product::getIsEnlisted, product::setIsEnlisted, request.isEnlisted());
        }
        if (request.isFeatured() != null) {
            changed |= updateIfChanged(product::getIsFeatured, product::setIsFeatured, request.isFeatured());
        }

        return changed;
    }

    private boolean updateImage(Product product, ProductRequest request){
        MultipartFile image = request.productImage();
        if (image == null || image.isEmpty()) return false;

        String uploadedImageUrl = cloudinaryFileUploadUtil.uploadProductImage(image);
        return updateIfChanged(product::getImageUrl, product::setImageUrl, uploadedImageUrl);
    }

    private <T> boolean updateIfChanged(Supplier<T> currentValue, Consumer<T> setter, T newValue) {
        if (Objects.equals(currentValue.get(), newValue)) return false;
        setter.accept(newValue);
        return true;
    }

    private boolean isMoneyDifferent(Money current, Money incoming) {
        if (current == null && incoming == null) return false;
        if (current == null || incoming == null) return true;

        boolean currencyDifferent = current.getCurrencyCode() != incoming.getCurrencyCode();
        boolean amountDifferent = current.getAmount() == null
                ? incoming.getAmount() != null
                : current.getAmount().compareTo(incoming.getAmount()) != 0;

        return currencyDifferent || amountDifferent;
    }

    @Override
    @CacheEvict(cacheNames = {CatalogCaches.PUBLIC_LISTINGS, CatalogCaches.PUBLIC_FEATURED, CatalogCaches.PRODUCT_DETAILS}, allEntries = true)
    @Transactional
    public void deleteProduct(Long productId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return; // idempotent: an unknown id is a no-op (204), as deleteById always was
        }
        ProductFamily family = product.getFamily();
        boolean isFamilyBase = family != null && family.getBaseUnit() != null && productId.equals(family.getBaseUnit().getId());
        if (!isFamilyBase) {
            productRepository.delete(product);
            productRepository.flush();
            return;
        }
        // API-03: every product created with isNewProduct=true is the base unit of its own family
        // (product_families.base_unit_id -> products, no cascade), so a plain delete always hit
        // FK_PRODUCT_FAMILIES_ON_BASE_UNIT. A family whose base unit is the only member goes with it; a family that
        // still has variants must lose them first (deleting the base would leave variants without a base unit).
        if (productRepository.countByFamilyAndIdNot(family, productId) > 0) {
            throw new ApiCodeException(HttpStatus.CONFLICT, FAMILY_HAS_VARIANTS, FAMILY_HAS_VARIANTS_MESSAGE);
        }
        family.setBaseUnit(null);
        productFamilyRepository.saveAndFlush(family);
        productRepository.delete(product);
        productRepository.flush();
        productFamilyRepository.delete(family);
        productFamilyRepository.flush();
    }

    public static final String FAMILY_HAS_VARIANTS = "FAMILY_HAS_VARIANTS";
    public static final String FAMILY_HAS_VARIANTS_MESSAGE =
            "This product is the base unit of a family that still has other units; delete those first";
}
