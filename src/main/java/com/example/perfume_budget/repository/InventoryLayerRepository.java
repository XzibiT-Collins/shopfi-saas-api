package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.InventoryLayer;
import com.example.perfume_budget.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryLayerRepository extends JpaRepository<InventoryLayer, Long> {
    List<InventoryLayer> findByProductOrderByReceivedAtAscIdAsc(Product product);
    List<InventoryLayer> findByProductIdOrderByReceivedAtAscIdAsc(Long productId);
    List<InventoryLayer> findByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(Long productId, Integer remainingQuantity);
    Optional<InventoryLayer> findFirstByProductIdAndRemainingQuantityGreaterThanOrderByReceivedAtAscIdAsc(Long productId, Integer remainingQuantity);
    boolean existsByProductId(Long productId);
}
