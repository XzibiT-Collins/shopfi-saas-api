package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.InventoryAllocationStatus;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.InventoryAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InventoryAllocationRepository extends JpaRepository<InventoryAllocation, Long> {

    @Query("select coalesce(sum(a.quantity), 0) from InventoryAllocation a " +
            "where a.product.id = :productId and a.status = com.example.perfume_budget.enums.InventoryAllocationStatus.RESERVED")
    int sumReservedQuantityByProductId(@Param("productId") Long productId);

    List<InventoryAllocation> findByReferenceTypeAndReferenceIdAndStatusOrderByIdAsc(
            InventoryReferenceType referenceType,
            String referenceId,
            InventoryAllocationStatus status
    );

    List<InventoryAllocation> findByReferenceTypeAndReferenceIdOrderByIdAsc(
            InventoryReferenceType referenceType,
            String referenceId
    );

    List<InventoryAllocation> findByReferenceTypeAndStatusAndCreatedAtBefore(
            InventoryReferenceType referenceType,
            InventoryAllocationStatus status,
            LocalDateTime createdAtBefore
    );
}
