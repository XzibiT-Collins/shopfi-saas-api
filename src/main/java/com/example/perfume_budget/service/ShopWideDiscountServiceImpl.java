package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.discount.request.ShopWideDiscountRequest;
import com.example.perfume_budget.dto.discount.response.ShopWideDiscountResponse;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.repository.ShopWideDiscountRepository;
import com.example.perfume_budget.service.interfaces.ShopWideDiscountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopWideDiscountServiceImpl implements ShopWideDiscountService {

    private final ShopWideDiscountRepository shopWideDiscountRepository;
    private final Clock systemClock;

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public ShopWideDiscountResponse setShopWideDiscount(ShopWideDiscountRequest request) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw new BadRequestException("Discount end date must be after the start date.");
        }

        // Only one shop-wide discount is applied at a time: retire any currently active ones.
        List<ShopWideDiscount> active = shopWideDiscountRepository.findByIsActiveTrueOrderByCreatedAtDesc();
        active.forEach(existing -> existing.setIsActive(false));
        shopWideDiscountRepository.saveAll(active);

        ShopWideDiscount discount = ShopWideDiscount.builder()
                .label(request.label())
                .discountPercentage(request.discountPercentage())
                .startAt(request.startAt())
                .endAt(request.endAt())
                .isActive(true)
                .build();

        return toResponse(shopWideDiscountRepository.save(discount));
    }

    @Override
    @Transactional(readOnly = true)
    public ShopWideDiscountResponse getCurrent() {
        ShopWideDiscount discount = shopWideDiscountRepository.findByIsActiveTrueOrderByCreatedAtDesc()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No active shop-wide discount."));
        return toResponse(discount);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"customerProductListings", "featuredProducts", "productDetailsPage"}, allEntries = true)
    public void deactivate(Long id) {
        ShopWideDiscount discount = shopWideDiscountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shop-wide discount not found."));
        discount.setIsActive(false);
        shopWideDiscountRepository.save(discount);
    }

    private ShopWideDiscountResponse toResponse(ShopWideDiscount discount) {
        LocalDateTime now = LocalDateTime.now(systemClock);
        boolean currentlyActive = Boolean.TRUE.equals(discount.getIsActive())
                && !now.isBefore(discount.getStartAt())
                && !now.isAfter(discount.getEndAt());
        return ShopWideDiscountResponse.builder()
                .id(discount.getId())
                .label(discount.getLabel())
                .discountPercentage(discount.getDiscountPercentage())
                .startAt(discount.getStartAt())
                .endAt(discount.getEndAt())
                .isActive(Boolean.TRUE.equals(discount.getIsActive()))
                .currentlyActive(currentlyActive)
                .build();
    }
}
