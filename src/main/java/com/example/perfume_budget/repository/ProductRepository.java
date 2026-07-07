package com.example.perfume_budget.repository;

import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.projection.LowStockProduct;
import com.example.perfume_budget.projection.TopProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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
    Optional<Product> findBySlug(String slug);
    Optional<Product> findBySlugAndIsActiveTrueAndIsEnlistedTrue(String slug);
    Optional<Product> findByIdAndIsActiveTrueAndIsEnlistedTrue(Long id);
    boolean existsBySku(String sku);
    List<Product> findByFamily(ProductFamily family);

    @Modifying
    @Transactional
    @Query("UPDATE Product p SET p.soldCount = p.soldCount + :quantity WHERE p.id = :productId")
    void incrementSoldCount(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying
    @Transactional
    @Query("UPDATE Product p SET p.viewCount = p.viewCount + 1L WHERE p.id = :productId")
    void incrementViewCount(@Param("productId") Long productId);

    @Modifying
    @Transactional
    @Query("UPDATE Product p SET p.addToCartCount = p.addToCartCount + 1L WHERE p.id = :productId")
    void incrementCartTimesCount(@Param("productId") Long productId);


    @Query("""
        SELECT p.id AS id, p.name AS productName,
               p.viewCount AS viewCount, p.soldCount AS soldCount,
               p.addToCartCount AS addToCartCount
        FROM Product p
        ORDER BY p.soldCount DESC
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

    Page<Product> findAllByIsActiveTrueAndIsEnlistedTrue(Pageable pageable);

    List<Product> findTop8ByIsActiveTrueAndIsEnlistedTrueAndIsFeaturedTrue();

    Page<Product> findAllByIsActiveTrue(Specification<Product> spec, Pageable pageable);
}
