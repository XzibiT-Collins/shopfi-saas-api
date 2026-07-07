package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.delivery_detail.request.DeliveryDetailRequest;
import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import com.example.perfume_budget.service.interfaces.DeliveryDetailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/delivery-detail")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class DeliveryDetailController {
    private final DeliveryDetailService deliveryDetailService;

    @PostMapping("/add-address")
    public ResponseEntity<CustomApiResponse<DeliveryDetailResponse>> addDeliveryAddress(
            @Valid @RequestBody DeliveryDetailRequest request){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(deliveryDetailService.addDeliveryDetail(request))
        );
    }

    @PutMapping("/update/{addressId}")
    public ResponseEntity<CustomApiResponse<DeliveryDetailResponse>> addDeliveryAddress(
            @Valid @RequestBody DeliveryDetailRequest request,
            @PathVariable Long addressId){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(deliveryDetailService.updateDeliveryDetail(addressId, request))
        );
    }

    @DeleteMapping("/delete/{addressId}")
    public ResponseEntity<Void> deleteAddress(@PathVariable Long addressId){
        deliveryDetailService.removeDeliveryDetail(addressId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<CustomApiResponse<List<DeliveryDetailResponse>>> getMyAddresses(){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(deliveryDetailService.getMyDeliveryDetails())
        );
    }

    @PutMapping("/{addressId}/set-default")
    public ResponseEntity<Void> setDefault(@PathVariable Long addressId){
        deliveryDetailService.setDefaultDeliveryDetail(addressId);
        return ResponseEntity.noContent().build();
    }
}
