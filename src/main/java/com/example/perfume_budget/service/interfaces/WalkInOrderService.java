package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.request.WalkInQuoteRequest;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInQuoteResponse;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface WalkInOrderService {
    /** Keyless sale (no replay protection). */
    default WalkInOrderResponse placeWalkInOrder(WalkInOrderRequest request) {
        return placeWalkInOrder(request, null);
    }

    /** {@code idempotencyKey} null/blank = keyless; otherwise one sale per (cashier, key), see docs/api/walk-in.md. */
    WalkInOrderResponse placeWalkInOrder(WalkInOrderRequest request, String idempotencyKey);

    /** Prices a draft sale without writing anything. */
    WalkInQuoteResponse quote(WalkInQuoteRequest request);

    List<CustomerSearchResponse> searchCustomers(String query);

    PageResponse<WalkInOrderResponse> getWalkInOrders(LocalDate date, Pageable pageable);

    WalkInOrderResponse getWalkInOrder(String orderNumber);

    void markReceiptPrinted(String orderNumber);
}
