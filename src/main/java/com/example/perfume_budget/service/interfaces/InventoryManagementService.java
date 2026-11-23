package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.inventory.request.InventoryAdjustmentRequest;
import com.example.perfume_budget.dto.inventory.request.InventoryReceiptRequest;
import com.example.perfume_budget.dto.inventory.response.InventoryMovementResponse;
import com.example.perfume_budget.dto.inventory.response.InventorySummaryResponse;
import com.example.perfume_budget.dto.inventory.response.ProductStockByLocationResponse;
import com.example.perfume_budget.enums.InventoryLayerSourceType;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.OrderItem;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.model.WalkInOrderItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface InventoryManagementService {
    InventorySummaryResponse receiveStock(InventoryReceiptRequest request);
    InventorySummaryResponse adjustInventory(InventoryAdjustmentRequest request);
    InventorySummaryResponse getProductInventorySummary(Long productId);
    List<InventoryMovementResponse> getProductInventoryHistory(Long productId);
    ProductStockByLocationResponse getProductStockByLocation(Long productId);

    /** INV-01b L3: read-only detection of legacy balances overwritten before INV-01 (at most 500 products). */
    com.example.perfume_budget.dto.inventory.response.LegacyBalanceReportResponse legacyBalanceReport();

    /** INV-01b L3: opt-in repair of exactly the given products (documentation/inventory-management-internal.md). */
    com.example.perfume_budget.dto.inventory.response.LegacyBalanceRepairResponse repairLegacyBalances(List<Long> productIds);

    void reserveOrderInventory(String orderNumber, List<OrderItem> orderItems);
    void releaseOrderInventory(String orderNumber);
    void finalizeReservedOrder(Order order);
    /**
     * Hands back the stock a PAID order consumed (admin cancel before dispatch): CONSUMED allocations go back to their
     * layers and become RETURNED, so a second call finds nothing to return (never double-restocks).
     * @return the number of units returned to stock
     */
    int restockConsumedOrderInventory(Order order);

    /** ORD-07: same as {@link #restockConsumedOrderInventory(Order)} with the note stored on the RETURN movements. */
    int restockConsumedOrderInventory(Order order, String movementNote);

    /** ORD-02: true while consumed (sold, not returned) stock of the order exists. */
    boolean hasConsumedOrderInventory(String orderNumber);
    void consumeWalkInInventory(String orderNumber, List<WalkInOrderItem> items);

    /**
     * API-RD-03 AC3: puts the units of a cancelled / refunded walk-in sale back into the layers they were taken from
     * (RETURN movements), at the location they left (SALE_RETURN) and takes them off the sold count. Idempotent by
     * construction: it restores what the order's SALE movements took MINUS what its RETURN movements already gave
     * back, so a second call restores nothing.
     *
     * @return the units put back (0 when everything was already returned)
     */
    int restoreWalkInInventory(String orderNumber);

    /**
     * API-05: row-locks the products (id order) for the rest of the caller's transaction before their stock changes.
     * Every stock-changing method here takes it itself; a caller that changes several products in one transaction
     * (stock conversion) takes them all first so two such transactions lock in the same order.
     */
    void lockProductsForStockUpdate(java.util.Collection<Long> productIds);

    InventoryConsumption consumeInventory(Product product,
                                          int quantity,
                                          InventoryReferenceType referenceType,
                                          String referenceId,
                                          String referenceLineKey,
                                          String note,
                                          boolean createReservation);

    Product createConversionLayer(Product product,
                                  int quantity,
                                  BigDecimal unitCost,
                                  BigDecimal unitSellingPrice,
                                  InventoryLayerSourceType sourceType,
                                  String sourceReference,
                                  String note,
                                  LocalDateTime receivedAt);

    record InventoryConsumption(List<InventoryConsumptionLine> lines, BigDecimal totalCost) {
    }

    record InventoryConsumptionLine(Long layerId,
                                    int quantity,
                                    BigDecimal unitCost,
                                    BigDecimal unitSellingPrice) {
    }
}
