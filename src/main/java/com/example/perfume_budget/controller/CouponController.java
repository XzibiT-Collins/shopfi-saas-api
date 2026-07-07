package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.service.interfaces.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/coupon")
@RequiredArgsConstructor
public class CouponController {
    private final CouponService couponService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/add-coupon")
    public ResponseEntity<CustomApiResponse<CouponDetailResponse>> createCoupon(@Valid @RequestBody CouponRequest request){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(couponService.createCoupon(request))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{couponId}")
    public ResponseEntity<CustomApiResponse<CouponDetailResponse>> getCouponById(@PathVariable Long couponId){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(couponService.getCouponById(couponId))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/all")
    public ResponseEntity<CustomApiResponse<PageResponse<CouponListResponse>>> getAll(Pageable pageable){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(couponService.getAllCoupons(pageable))
        );
    }
}
