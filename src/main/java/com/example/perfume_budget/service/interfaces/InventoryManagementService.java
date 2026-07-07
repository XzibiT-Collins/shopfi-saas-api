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

    void reserveOrderInventory(String orderNumber, List<OrderItem> orderItems);
    void releaseOrderInventory(String orderNumber);
    void finalizeReservedOrder(Order order);
    void consumeWalkInInventory(String orderNumber, List<WalkInOrderItem> items);

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
