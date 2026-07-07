package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.discount.request.ShopWideDiscountRequest;
import com.example.perfume_budget.dto.discount.response.ShopWideDiscountResponse;

public interface ShopWideDiscountService {
    ShopWideDiscountResponse setShopWideDiscount(ShopWideDiscountRequest request);
    ShopWideDiscountResponse getCurrent();
    void deactivate(Long id);
}
