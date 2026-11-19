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

    /**
     * INV-01b L3: products whose pre-INV-01 receipt overwrote a legacy balance. The signature: no LEGACY_BOOTSTRAP movement,
     * a PURCHASE layer and no OPENING_STOCK layer, and a location ledger (seeded from the legacy balance by V5) that
     * disagrees with {@code stock + outstanding reservations}.
     */
    String LEGACY_CANDIDATES_SQL = """
            SELECT c.id AS productId, c.sku AS sku, c.name AS name, c.stock AS stockQuantity,
                   c.reserved AS reservedQuantity, c.on_hand AS locationOnHand
            FROM (
              SELECT p.id, p.sku, p.name, COALESCE(p.stock_quantity, 0) AS stock,
                     CAST(COALESCE((SELECT SUM(a.quantity) FROM inventory_allocations a
                                    WHERE a.product_id = p.id AND a.status = 'RESERVED'), 0) AS INTEGER) AS reserved,
                     CAST(COALESCE((SELECT SUM(ls.quantity_on_hand) FROM location_stock ls
                                    WHERE ls.product_id = p.id), 0) AS INTEGER) AS on_hand
              FROM products p
              WHERE EXISTS (SELECT 1 FROM inventory_layers l WHERE l.product_id = p.id AND l.source_type = 'PURCHASE')
                AND NOT EXISTS (SELECT 1 FROM inventory_layers l WHERE l.product_id = p.id AND l.source_type = 'OPENING_STOCK')
                AND NOT EXISTS (SELECT 1 FROM inventory_movements m WHERE m.product_id = p.id AND m.movement_type = 'LEGACY_BOOTSTRAP')
            ) c
            WHERE c.on_hand <> c.stock + c.reserved
            """;

    @org.springframework.data.jpa.repository.Query(value = LEGACY_CANDIDATES_SQL + " ORDER BY c.id LIMIT 501", nativeQuery = true)
    List<com.example.perfume_budget.projection.LegacyBalanceCandidate> findLegacyBalanceCandidates();

    @org.springframework.data.jpa.repository.Query(value = LEGACY_CANDIDATES_SQL + " AND c.id = :productId", nativeQuery = true)
    Optional<com.example.perfume_budget.projection.LegacyBalanceCandidate> findLegacyBalanceCandidate(
            @org.springframework.data.repository.query.Param("productId") Long productId);
}
