package com.example.perfume_budget.repository;

import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.projection.LowStockProduct;
import com.example.perfume_budget.projection.TopProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    /*
     * CAT-01: open-in-view is off and Product.category is LAZY, so every catalog read that maps a product to a DTO
     * outside a transaction must load the category in the same query (entity graph = one LEFT JOIN, no N+1).
     * Without it ProductMapper's getCategory().getName() throws LazyInitializationException (500).
     */
    String CATEGORY = "category";

    Optional<Product> findBySlug(String slug);

    /** Public detail page (cached as a DTO by ProductCatalogCacheService). */
    @EntityGraph(attributePaths = CATEGORY)
    Optional<Product> findBySlugAndIsActiveTrueAndIsEnlistedTrue(String slug);

    /** Admin detail ({@code GET /product/main/{id}}). */
    @EntityGraph(attributePaths = CATEGORY)
    Optional<Product> findWithCategoryById(Long id);

    /** Admin listing. */
    @Override
    @EntityGraph(attributePaths = CATEGORY)
    Page<Product> findAll(Pageable pageable);

    /** Public and admin search. */
    @Override
    @EntityGraph(attributePaths = CATEGORY)
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    Optional<Product> findByIdAndIsActiveTrueAndIsEnlistedTrue(Long id);

    /** CAT-07: public lookup by ids (active + listed only, category in the same query). Order is the caller's. */
    @EntityGraph(attributePaths = CATEGORY)
    List<Product> findAllByIdInAndIsActiveTrueAndIsEnlistedTrue(java.util.Collection<Long> ids);

    /** Existence check for theme references (THEME-02): which of {@code ids} exist in the current tenant. */
    @org.springframework.data.jpa.repository.Query("select p.id from Product p where p.id in :ids")
    java.util.List<Long> findExistingIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    boolean existsBySku(String sku);

    /** API-03: the other members of a family (delete of its base unit). */
    long countByFamilyAndIdNot(ProductFamily family, Long id);
    /** CAT-02: the family's members with their unit of measure in the same query (available-uoms maps the code). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "unitOfMeasure")
    List<Product> findByFamily(ProductFamily family);

    /**
     * API-05: row locks of the products whose stock is about to change, taken in id order (one statement, ORDER BY
     * before the lock step) so two multi-product carts can never deadlock. {@code FOR NO KEY UPDATE}: does not wait
     * for the FK key-share locks that an in-flight checkout's order / allocation rows hold on the product (FOR UPDATE
     * would). Every writer of inventory layers / product stock takes these locks first (InventoryManagementServiceImpl).
     */
    @Query(value = "SELECT id FROM products WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE", nativeQuery = true)
    List<Long> lockForStockUpdate(@Param("ids") java.util.Collection<Long> ids);

    @Modifying
    @Transactional
    @Query("UPDATE Product p SET p.soldCount = COALESCE(p.soldCount, 0) + :quantity WHERE p.id = :productId")
    void incrementSoldCount(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying
    @Transactional
    @Query("UPDATE Product p SET p.viewCount = COALESCE(p.viewCount, 0L) + 1L WHERE p.id = :productId")
    void incrementViewCount(@Param("productId") Long productId);

    @Modifying
    @Transactional
    @Query("UPDATE Product p SET p.addToCartCount = COALESCE(p.addToCartCount, 0L) + 1L WHERE p.id = :productId")
    void incrementCartTimesCount(@Param("productId") Long productId);


    @Query("""
        SELECT p.id AS id, p.name AS productName,
               COALESCE(p.viewCount, 0L) AS viewCount, COALESCE(p.soldCount, 0) AS soldCount,
               COALESCE(p.addToCartCount, 0L) AS addToCartCount
        FROM Product p
        ORDER BY COALESCE(p.soldCount, 0) DESC, p.id ASC
        LIMIT 6
        """)
    List<TopProduct> findTopSixMostSoldProducts();

    @Query("""
        SELECT p.id AS id, p.name AS productName,
               p.imageUrl AS productImage, p.stockQuantity AS stockQuantity
        FROM Product p
        WHERE p.stockQuantity <= p.lowStockThreshold
        ORDER BY p.stockQuantity ASC
        """)
    List<LowStockProduct> findLowStockProducts(Pageable pageable);

    /** Public listing (cached as a DTO page). */
    @EntityGraph(attributePaths = CATEGORY)
    Page<Product> findAllByIsActiveTrueAndIsEnlistedTrue(Pageable pageable);

    /** Public featured list (cached as DTOs). */
    @EntityGraph(attributePaths = CATEGORY)
    List<Product> findTop8ByIsActiveTrueAndIsEnlistedTrueAndIsFeaturedTrue();

    /**
     * CAT-05: the featured shelf restricted to products with stock (GET /product/featured?inStock=true).
     * HOTFIX-1: needs the same category fetch as its sibling, or the mapper's getCategory().getName() throws
     * LazyInitializationException (open-in-view is off) for any categorised product.
     */
    @EntityGraph(attributePaths = CATEGORY)
    List<Product> findTop8ByIsActiveTrueAndIsEnlistedTrueAndIsFeaturedTrueAndStockQuantityGreaterThan(int stockQuantity);

    Page<Product> findAllByIsActiveTrue(Specification<Product> spec, Pageable pageable);
}
