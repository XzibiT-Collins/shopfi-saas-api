package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.delivery_detail.request.DeliveryDetailRequest;
import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;

import java.util.List;

public interface DeliveryDetailService {
    DeliveryDetailResponse addDeliveryDetail(DeliveryDetailRequest request);
    DeliveryDetailResponse updateDeliveryDetail(Long deliveryDetailId, DeliveryDetailRequest request);
    void removeDeliveryDetail(Long deliveryDetailId);
    List<DeliveryDetailResponse> getMyDeliveryDetails();
    void setDefaultDeliveryDetail(Long deliveryDetailId);
}
