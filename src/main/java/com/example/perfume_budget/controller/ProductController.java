package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.request.ProductDiscountRequest;
import com.example.perfume_budget.dto.product.request.ProductRequest;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.dto.product.response.PublicProductListing;
import com.example.perfume_budget.security.ClientIps;
import com.example.perfume_budget.service.interfaces.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/product")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;
    private final com.example.perfume_budget.service.ProductViewDeduplicator productViewDeduplicator;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/add-product", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CustomApiResponse<ProductDetails>> createProduct(@Valid @ModelAttribute ProductRequest request){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(productService.createProduct(request))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping(value = "/update/{productId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CustomApiResponse<ProductDetails>> updateProduct(
            @PathVariable Long productId,
            @Valid @ModelAttribute ProductRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.updateProduct(productId, request)));
    }

    @GetMapping("/featured")
    public ResponseEntity<CustomApiResponse<List<PublicProductListing>>> getFeaturedProducts(){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.getFeaturedProducts()));
    }

    @GetMapping("/listing")
    public ResponseEntity<CustomApiResponse<PageResponse<PublicProductListing>>> getProductListings(Pageable pageable){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.getProductListings(pageable)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/listing")
    public ResponseEntity<CustomApiResponse<PageResponse<ProductListing>>> getAdminProductListings(Pageable pageable){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.getAdminProductListings(pageable)));
    }

    @GetMapping("/search")
    public ResponseEntity<CustomApiResponse<PageResponse<PublicProductListing>>> searchProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String searchTerm,
            Pageable pageable){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.searchProducts(categoryId, searchTerm, pageable)));
    }

    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @GetMapping("/admin/search")
    public ResponseEntity<CustomApiResponse<PageResponse<ProductListing>>> searchAdminProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String searchTerm,
            Pageable pageable){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.searchAdminProducts(categoryId, searchTerm, pageable)));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<CustomApiResponse<ProductDetailsPageResponse>> getProductDetailsPage(
            @PathVariable String slug, jakarta.servlet.http.HttpServletRequest request){
        ProductDetailsPageResponse page = productService.getProductDetailsPage(slug, false);
        // HEAD runs this same handler; it must not bump the view counter (SEC-03b F11). SEC-03c: a GET counts at most
        // once per client and product per window, so reloads and GET loops do not inflate view_count.
        if (!"HEAD".equalsIgnoreCase(request.getMethod())
                && productViewDeduplicator.firstView(page.productId(), ClientIps.rateLimitKey(request))) {
            productService.recordProductView(page.productId());
        }
        return ResponseEntity.ok().body(CustomApiResponse.success(page));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/main/{productId}")
    public ResponseEntity<CustomApiResponse<ProductDetails>> getProductDetails(@PathVariable Long productId){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.getProductDetails(productId)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/admin/{productId}/discount")
    public ResponseEntity<CustomApiResponse<ProductDetails>> setProductDiscount(
            @PathVariable Long productId,
            @Valid @RequestBody ProductDiscountRequest request){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.setProductDiscount(productId, request)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/admin/{productId}/discount")
    public ResponseEntity<CustomApiResponse<ProductDetails>> clearProductDiscount(@PathVariable Long productId){
        return ResponseEntity.ok().body(CustomApiResponse.success(productService.clearProductDiscount(productId)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/delete/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId){
        productService.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }
}
