package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.ws.NotificationService;
import com.example.perfume_budget.enums.StorageLocationType;
import com.example.perfume_budget.events.ShopFloorStockEvent;
import com.example.perfume_budget.model.LocationStock;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.LocationStockRepository;
import com.example.perfume_budget.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShopFloorStockHandler {
    private final LocationStockRepository locationStockRepository;
    private final ProductRepository productRepository;
    private final NotificationService notificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleShopFloorStockChange(ShopFloorStockEvent event) {
        try {
            // Runs after the producing transaction committed — re-query everything.
            List<LocationStock> floorRows = locationStockRepository
                    .findByProductIdAndLocationType(event.productId(), StorageLocationType.SHOP_FLOOR);
            if (floorRows.isEmpty()) {
                return;
            }

            Product product = productRepository.findById(event.productId()).orElse(null);
            if (product == null) {
                return;
            }

            int floorQuantity = floorRows.stream().mapToInt(LocationStock::getQuantityOnHand).sum();
            int threshold = floorRows.stream()
                    .map(row -> row.getLocation().getLowStockThreshold())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseGet(() -> product.getLowStockThreshold() != null ? product.getLowStockThreshold() : 0);

            int storeRoomQuantity = locationStockRepository
                    .sumQuantityByProductAndLocationType(event.productId(), StorageLocationType.STORE_ROOM);

            if (floorQuantity <= threshold && storeRoomQuantity > 0) {
                notificationService.notifyStaffOfShopFloorShortage(
                        event.productId(), product.getName(), floorQuantity, storeRoomQuantity);
            }
        } catch (Exception e) {
            log.error("Failed to process shop floor stock event for product {}", event.productId(), e);
        }
    }
}
