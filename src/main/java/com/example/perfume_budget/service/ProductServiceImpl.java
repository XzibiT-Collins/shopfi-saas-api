package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.request.ProductDiscountRequest;
import com.example.perfume_budget.dto.product.request.ProductRequest;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.exception.ResourceNotFoundException;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public ProductDetails createProduct(ProductRequest request) {
        checkIfImageIsPresentForEcommerceProduct(request);

        String uploadedImageUrl = uploadToCloudIfImageIsPresent(request.productImage());

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

    private ProductDetails createNewProduct(Product newProduct, ProductRequest request, String size) {
        ProductFamily family;
        UnitOfMeasure uom;
        if (Boolean.TRUE.equals(request.isNewProduct())) {
            // SCENARIO 1: New Product (Creates new family + base EA variant)
            String familyCode = ProductCodeGenerator.generateFamilyCode(request.productName(), request.brand(), request.size());
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
                throw new BadRequestException("Variant with UOM " + uom.getCode() + " already exists for this family.");
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
            BigDecimal calculatedCost = baseUnit.getCostPrice().getAmount().multiply(BigDecimal.valueOf(convFactor));
            newProduct.setCostPrice(new Money(calculatedCost, baseUnit.getCostPrice().getCurrencyCode()));

            Product savedProduct = productRepository.save(newProduct);
            return toDetails(savedProduct);
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

    @Override
    @Cacheable(
            cacheNames = "customerProductListings",
            key = "#pageable.pageNumber + ':' + #pageable.pageSize + ':' + #pageable.sort.toString()"
    )
    public PageResponse<ProductListing> getProductListings(Pageable pageable) {
        log.info("Cache miss: customerProductListings");
        Page<ProductListing> productListings = productRepository.findAllByIsActiveTrueAndIsEnlistedTrue(pageable)
                .map(listingMapper());
        return PaginationUtil.createPageResponse(productListings);
    }

    @Override
    public PageResponse<ProductListing> searchProducts(Long categoryId, String searchTerm, Pageable pageable) {
        Specification<Product> spec = ProductSpecification.filterProducts(categoryId, searchTerm);
        Page<ProductListing> productListings = productRepository.findAll(spec, pageable).map(listingMapper());
        return PaginationUtil.createPageResponse(productListings);
    }

    @Override
    public PageResponse<ProductListing> getAdminProductListings(Pageable pageable) {
        Page<ProductListing> productListings = productRepository.findAll(pageable).map(listingMapper());
        return PaginationUtil.createPageResponse(productListings);
    }

    @Override
    public List<ProductListing> getFeaturedProducts() {
        return productCatalogCacheService.getFeaturedProducts();
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
        Product product = productRepository.findById(productId).orElseThrow(() -> new ResourceNotFoundException(PRODUCT_NOT_FOUND));
        return toDetails(product);
    }

    @Override
    public ProductDetailsPageResponse getProductDetailsPage(String slug) {
        ProductDetailsPageResponse response = productCatalogCacheService.getProductDetailsPage(slug);
        updateProductViewCount(response.productId());
        return response;
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public ProductDetails updateProduct(Long productId, ProductRequest request) {
        Product productToUpdate = productRepository.findById(productId).orElseThrow(() -> new ResourceNotFoundException(PRODUCT_NOT_FOUND));
        checkIfImageIsPresentForEcommerceProductUpdate(request, productToUpdate);
        Product updatedProduct = checkAndUpdateProductFields(productToUpdate, request);
        return toDetails(updatedProduct);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
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
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
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

    private void updateProductViewCount(Long id) {
        log.info("Updating product view count for product: {}", id);
        User currentUser = authUserUtil.getCurrentUser();
        boolean isAdminUser = currentUser != null &&
                currentUser.getRoles().equals(UserRole.ADMIN);
        if (!isAdminUser) {
            log.info("User is not an admin");
            eventPublisher.publishEvent(ViewCountEvent.builder().productId(id).build());
        }else{
            log.info("current user is an Admin");
        }
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
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public void deleteProduct(Long productId) {
        productRepository.deleteById(productId);
    }
}
