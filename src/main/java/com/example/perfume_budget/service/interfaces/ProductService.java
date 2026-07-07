package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.request.ProductDiscountRequest;
import com.example.perfume_budget.dto.product.request.ProductRequest;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.util.List;

public interface ProductService {
    ProductDetails createProduct(ProductRequest request);
    PageResponse<ProductListing> getProductListings(Pageable pageable);
    PageResponse<ProductListing> searchProducts(Long categoryId, String searchTerm, Pageable pageable);
    PageResponse<ProductListing> getAdminProductListings(Pageable pageable);

    List<ProductListing> getFeaturedProducts();

    PageResponse<ProductListing> searchAdminProducts(Long categoryId, String searchTerm, Pageable pageable);
    ProductDetails getProductDetails(Long productId);
    ProductDetailsPageResponse getProductDetailsPage(String slug);
    ProductDetails updateProduct(Long productId, ProductRequest request);
    ProductDetails setProductDiscount(Long productId, ProductDiscountRequest request);
    ProductDetails clearProductDiscount(Long productId);
    void deleteProduct(Long productId);
}
