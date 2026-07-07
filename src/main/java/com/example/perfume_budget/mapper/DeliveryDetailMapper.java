package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.delivery_detail.request.DeliveryDetailRequest;
import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import com.example.perfume_budget.model.DeliveryDetail;

public class DeliveryDetailMapper {
    private DeliveryDetailMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static DeliveryDetailResponse toDeliveryDetailResponse(DeliveryDetail detail){
        return DeliveryDetailResponse.builder()
                .id(detail.getId())
                .recipientName(detail.getRecipientName())
                .phoneNumber(detail.getPhoneNumber())
                .alternatePhoneNumber(detail.getAlternatePhoneNumber())
                .addressLine1(detail.getAddressLine1())
                .addressLine2(detail.getAddressLine2())
                .city(detail.getCity())
                .region(detail.getRegion())
                .landmark(detail.getLandmark())
                .label(detail.getLabel())
                .isDefault(detail.getIsDefault())
                .build();
    }

    public static DeliveryDetail toDeliveryDetail(DeliveryDetailRequest request){
        return DeliveryDetail.builder()
                .recipientName(request.recipientName())
                .phoneNumber(request.phoneNumber())
                .alternatePhoneNumber(request.alternatePhoneNumber())
                .addressLine1(request.addressLine1())
                .addressLine2(request.addressLine2())
                .city(request.city())
                .region(request.region())
                .label(request.label())
                .landmark(request.landmark())
                .isDefault(request.isDefault())
                .build();
    }
}
