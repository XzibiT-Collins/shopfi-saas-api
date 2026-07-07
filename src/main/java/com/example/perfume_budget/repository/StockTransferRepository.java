package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.StockTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    @Query("select t from StockTransfer t " +
            "where (:productId is null or t.product.id = :productId) " +
            "and (:locationId is null or t.fromLocation.id = :locationId or t.toLocation.id = :locationId) " +
            "order by t.createdAt desc, t.id desc")
    Page<StockTransfer> findHistory(@Param("productId") Long productId,
                                    @Param("locationId") Long locationId,
                                    Pageable pageable);

    // Per-product sale events for the items-sold detail report: one row per deduction,
    // carrying the source location, historical balance-after, and the seller (movedBy).
    @Query(value = "select t from StockTransfer t " +
            "left join fetch t.fromLocation left join fetch t.movedBy " +
            "where t.product.id = :productId " +
            "and t.transferType = com.example.perfume_budget.enums.StockTransferType.SALE_DEDUCTION " +
            "and t.referenceType in :refTypes " +
            "and t.createdAt between :start and :end " +
            "order by t.createdAt desc, t.id desc",
            countQuery = "select count(t) from StockTransfer t " +
            "where t.product.id = :productId " +
            "and t.transferType = com.example.perfume_budget.enums.StockTransferType.SALE_DEDUCTION " +
            "and t.referenceType in :refTypes " +
            "and t.createdAt between :start and :end")
    Page<StockTransfer> findSaleDeductions(@Param("productId") Long productId,
                                           @Param("refTypes") List<InventoryReferenceType> refTypes,
                                           @Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           Pageable pageable);
}
