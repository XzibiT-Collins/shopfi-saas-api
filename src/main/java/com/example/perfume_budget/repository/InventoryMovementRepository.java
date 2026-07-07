package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.InventoryMovement;
import com.example.perfume_budget.projection.SaleReferenceAmount;
import com.example.perfume_budget.projection.SalesTotals;
import com.example.perfume_budget.projection.SoldProductAggregate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {
    List<InventoryMovement> findByProductIdOrderByCreatedAtDescIdDesc(Long productId);

    // ---- Items-sold report ------------------------------------------------

    @Query(value = """
            select p.id as productId, p.name as productName, p.sku as sku,
                   p.imageUrl as imageUrl,
                   sum(m.quantity) as quantitySold,
                   sum(m.quantity * m.unitSellingPrice) as totalAmount,
                   p.stockQuantity as remainingStock,
                   p.lowStockThreshold as lowStockThreshold
            from InventoryMovement m join m.product p
            where m.movementType = com.example.perfume_budget.enums.InventoryMovementType.SALE
              and m.referenceType in :refTypes
              and m.createdAt between :start and :end
            group by p.id, p.name, p.sku, p.imageUrl, p.stockQuantity
            order by sum(m.quantity * m.unitSellingPrice) desc""",
            countQuery = """
            select count(distinct p.id)
            from InventoryMovement m join m.product p
            where m.movementType = com.example.perfume_budget.enums.InventoryMovementType.SALE
              and m.referenceType in :refTypes
              and m.createdAt between :start and :end""")
    Page<SoldProductAggregate> findSoldProductAggregates(@Param("refTypes") List<InventoryReferenceType> refTypes,
                                                         @Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end,
                                                         Pageable pageable);

    @Query("""
            select coalesce(sum(m.quantity), 0) as totalQuantity,
                   coalesce(sum(m.quantity * m.unitSellingPrice), 0) as totalAmount
            from InventoryMovement m
            where m.movementType = com.example.perfume_budget.enums.InventoryMovementType.SALE
              and m.referenceType in :refTypes
              and m.createdAt between :start and :end""")
    SalesTotals sumSales(@Param("refTypes") List<InventoryReferenceType> refTypes,
                         @Param("start") LocalDateTime start,
                         @Param("end") LocalDateTime end);

    @Query("""
            select coalesce(sum(m.quantity), 0) as totalQuantity,
                   coalesce(sum(m.quantity * m.unitSellingPrice), 0) as totalAmount
            from InventoryMovement m
            where m.movementType = com.example.perfume_budget.enums.InventoryMovementType.SALE
              and m.product.id = :productId
              and m.referenceType in :refTypes
              and m.createdAt between :start and :end""")
    SalesTotals sumSalesForProduct(@Param("productId") Long productId,
                                   @Param("refTypes") List<InventoryReferenceType> refTypes,
                                   @Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end);

    @Query("""
            select m.referenceType as referenceType, m.referenceId as referenceId,
                   sum(m.quantity * m.unitSellingPrice) as amount
            from InventoryMovement m
            where m.movementType = com.example.perfume_budget.enums.InventoryMovementType.SALE
              and m.product.id = :productId
              and m.referenceType in :refTypes
              and m.createdAt between :start and :end
            group by m.referenceType, m.referenceId""")
    List<SaleReferenceAmount> sumSaleAmountsByReference(@Param("productId") Long productId,
                                                        @Param("refTypes") List<InventoryReferenceType> refTypes,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    @Query("""
            select distinct m.currencyCode
            from InventoryMovement m
            where m.movementType = com.example.perfume_budget.enums.InventoryMovementType.SALE
              and m.referenceType in :refTypes
              and m.createdAt between :start and :end""")
    List<CurrencyCode> findSaleCurrencies(@Param("refTypes") List<InventoryReferenceType> refTypes,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);
}
