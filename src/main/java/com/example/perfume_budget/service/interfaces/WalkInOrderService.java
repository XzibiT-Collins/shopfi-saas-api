package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.walk_in.request.WalkInOrderRequest;
import com.example.perfume_budget.dto.walk_in.response.CustomerSearchResponse;
import com.example.perfume_budget.dto.walk_in.response.WalkInOrderResponse;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface WalkInOrderService {
    WalkInOrderResponse placeWalkInOrder(WalkInOrderRequest request);

    List<CustomerSearchResponse> searchCustomers(String query);

    PageResponse<WalkInOrderResponse> getWalkInOrders(LocalDate date, Pageable pageable);

    WalkInOrderResponse getWalkInOrder(String orderNumber);

    void markReceiptPrinted(String orderNumber);
}
