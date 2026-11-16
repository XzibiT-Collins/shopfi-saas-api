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

    PageResponse<ProductListing> searchAdminProducts(Long categoryId, String searchTerm, Pageable pageable);
    ProductDetails getProductDetails(Long productId);
    default ProductDetailsPageResponse getProductDetailsPage(String slug) {
        return getProductDetailsPage(slug, true);
    }

    /** {@code countView=false} for HEAD: the response is identical but no view is recorded. */
    ProductDetailsPageResponse getProductDetailsPage(String slug, boolean countView);

    /** Records one storefront view of the product (skipped for an admin caller). SEC-03c: deduplicated by the caller. */
    void recordProductView(Long productId);
    ProductDetails updateProduct(Long productId, ProductRequest request);
    ProductDetails setProductDiscount(Long productId, ProductDiscountRequest request);
    ProductDetails clearProductDiscount(Long productId);
    void deleteProduct(Long productId);
}
