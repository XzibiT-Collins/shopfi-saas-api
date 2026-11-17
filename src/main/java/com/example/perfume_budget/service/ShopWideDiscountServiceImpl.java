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
import org.springframework.data.domain.PageRequest;
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
    @CacheEvict(cacheNames = {"publicCustomerProductListings", "publicFeaturedProducts", "productDetailsPage"}, allEntries = true)
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
        // API-04: only a discount that is in force right now (active flag AND inside its window); an expired or
        // not-yet-started row is "none" (404), exactly what EffectivePriceService prices with.
        ShopWideDiscount discount = shopWideDiscountRepository.findActiveNow(LocalDateTime.now(systemClock))
                .orElseThrow(() -> new ResourceNotFoundException("No active shop-wide discount."));
        return toResponse(discount);
    }

    /** Upper bound on {@link #listNotEnded()}; setting a discount retires the others, so normally there is one row. */
    static final int LIST_LIMIT = 50;

    @Override
    @Transactional(readOnly = true)
    public List<ShopWideDiscountResponse> listNotEnded() {
        // API-06: unlike getCurrent this includes a discount whose start is still in the future, so the admin can read
        // a scheduled discount back (e.g. after a page reload) and end it with DELETE /{id} before it starts.
        LocalDateTime now = LocalDateTime.now(systemClock);
        return shopWideDiscountRepository.findNotEnded(now, PageRequest.of(0, LIST_LIMIT)).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {"publicCustomerProductListings", "publicFeaturedProducts", "productDetailsPage"}, allEntries = true)
    public void deactivate(Long id) {
        ShopWideDiscount discount = shopWideDiscountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shop-wide discount not found."));
        discount.setIsActive(false);
        shopWideDiscountRepository.save(discount);
    }

    private ShopWideDiscountResponse toResponse(ShopWideDiscount discount) {
        LocalDateTime now = LocalDateTime.now(systemClock);
        boolean switchedOn = Boolean.TRUE.equals(discount.getIsActive());
        boolean currentlyActive = switchedOn
                && !now.isBefore(discount.getStartAt())
                && !now.isAfter(discount.getEndAt());
        ShopWideDiscountResponse.Status status;
        if (!switchedOn) {
            status = ShopWideDiscountResponse.Status.INACTIVE;
        } else if (now.isBefore(discount.getStartAt())) {
            status = ShopWideDiscountResponse.Status.SCHEDULED;
        } else if (now.isAfter(discount.getEndAt())) {
            status = ShopWideDiscountResponse.Status.EXPIRED;
        } else {
            status = ShopWideDiscountResponse.Status.ACTIVE;
        }
        return ShopWideDiscountResponse.builder()
                .id(discount.getId())
                .label(discount.getLabel())
                .discountPercentage(discount.getDiscountPercentage())
                .startAt(discount.getStartAt())
                .endAt(discount.getEndAt())
                .isActive(Boolean.TRUE.equals(discount.getIsActive()))
                .currentlyActive(currentlyActive)
                .status(status)
                .build();
    }
}
