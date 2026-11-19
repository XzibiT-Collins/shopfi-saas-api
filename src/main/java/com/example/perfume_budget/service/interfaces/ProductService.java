package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.request.ProductDiscountRequest;
import com.example.perfume_budget.dto.product.request.ProductRequest;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.dto.product.response.PublicProductListing;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.util.List;

public interface ProductService {
    ProductDetails createProduct(ProductRequest request);
    PageResponse<PublicProductListing> getProductListings(Pageable pageable);
    PageResponse<PublicProductListing> searchProducts(Long categoryId, String searchTerm, Pageable pageable);
    PageResponse<ProductListing> getAdminProductListings(Pageable pageable);

    List<PublicProductListing> getFeaturedProducts();

    /** CAT-05: {@code inStockOnly} keeps only products with stock, still at most 8. */
    List<PublicProductListing> getFeaturedProducts(boolean inStockOnly);

    PageResponse<ProductListing> searchAdminProducts(Long categoryId, String searchTerm, Pageable pageable);
    ProductDetails getProductDetails(Long productId);
    /** The public product page. Read only: it never counts a view (VIEW-01, see {@link #recordView}). */
    ProductDetailsPageResponse getProductDetailsPage(String slug);

    /** CAT-07: the active, listed products among {@code ids} (tenant scoped), in the order of {@code ids}. */
    List<PublicProductListing> getPublicProductsByIds(List<Long> ids);

    /**
     * VIEW-01: the view beacon. Rate limited per {@code clientKey}; a bot/prefetch request, an unknown or unlisted
     * product, an ADMIN session and a repeat inside the dedupe window are not counted.
     *
     * @return true when a view was counted
     * @throws com.example.perfume_budget.exception.RateLimitExceededException when the client key is over the limit
     */
    boolean recordView(String slug, String clientKey, boolean automated);
    ProductDetails updateProduct(Long productId, ProductRequest request);
    ProductDetails setProductDiscount(Long productId, ProductDiscountRequest request);
    ProductDetails clearProductDiscount(Long productId);
    void deleteProduct(Long productId);
}
