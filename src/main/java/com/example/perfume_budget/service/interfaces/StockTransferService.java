package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.inventory.request.StockTransferRequest;
import com.example.perfume_budget.dto.inventory.response.StockTransferResponse;
import org.springframework.data.domain.Page;

public interface StockTransferService {
    StockTransferResponse transfer(StockTransferRequest request);

    Page<StockTransferResponse> history(Long productId, Long locationId, int page, int size);
}
