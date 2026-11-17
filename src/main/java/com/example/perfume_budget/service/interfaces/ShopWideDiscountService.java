package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.discount.request.ShopWideDiscountRequest;
import com.example.perfume_budget.dto.discount.response.ShopWideDiscountResponse;

public interface ShopWideDiscountService {
    ShopWideDiscountResponse setShopWideDiscount(ShopWideDiscountRequest request);
    ShopWideDiscountResponse getCurrent();

    /** API-06: the discounts that are switched on and not over yet (in force now or scheduled), soonest start first. */
    java.util.List<ShopWideDiscountResponse> listNotEnded();
    void deactivate(Long id);
}
