package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.delivery_detail.request.DeliveryDetailRequest;
import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.DeliveryDetailMapper;
import com.example.perfume_budget.model.DeliveryDetail;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import com.example.perfume_budget.service.interfaces.DeliveryDetailService;
import com.example.perfume_budget.utils.AuthUserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryDetailServiceImpl implements DeliveryDetailService {
    private final DeliveryDetailRepository deliveryDetailRepository;
    private final AuthUserUtil authUserUtil;
    private static final String ADDRESS_NOT_FOUND = "Address not found";

    @Override
    public DeliveryDetailResponse addDeliveryDetail(DeliveryDetailRequest request) {
        User user = authUserUtil.getCurrentUser();
        DeliveryDetail newDeliveryDetail = DeliveryDetailMapper.toDeliveryDetail(request);
        newDeliveryDetail.setUser(user);
        if(Boolean.TRUE.equals(newDeliveryDetail.getIsDefault())){
            deliveryDetailRepository.clearDefaultForUser(user.getId());
        }
        return DeliveryDetailMapper.toDeliveryDetailResponse(deliveryDetailRepository.save(newDeliveryDetail));
    }

    @Override
    public DeliveryDetailResponse updateDeliveryDetail(Long deliveryDetailId, DeliveryDetailRequest request) {
        User currentUser = authUserUtil.getCurrentUser();

        DeliveryDetail deliveryDetail = deliveryDetailRepository.findByIdAndUser(deliveryDetailId, currentUser)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery address not found"));

        boolean changed = false;

        if (request.recipientName() != null)
            changed |= updateIfChanged(deliveryDetail::getRecipientName, deliveryDetail::setRecipientName, request.recipientName());

        if (request.phoneNumber() != null)
            changed |= updateIfChanged(deliveryDetail::getPhoneNumber, deliveryDetail::setPhoneNumber, request.phoneNumber());

        if (request.alternatePhoneNumber() != null)
            changed |= updateIfChanged(deliveryDetail::getAlternatePhoneNumber, deliveryDetail::setAlternatePhoneNumber, request.alternatePhoneNumber());

        if (request.addressLine1() != null)
            changed |= updateIfChanged(deliveryDetail::getAddressLine1, deliveryDetail::setAddressLine1, request.addressLine1());

        if (request.addressLine2() != null)
            changed |= updateIfChanged(deliveryDetail::getAddressLine2, deliveryDetail::setAddressLine2, request.addressLine2());

        if (request.city() != null)
            changed |= updateIfChanged(deliveryDetail::getCity, deliveryDetail::setCity, request.city());

        if (request.region() != null)
            changed |= updateIfChanged(deliveryDetail::getRegion, deliveryDetail::setRegion, request.region());

        if (request.landmark() != null)
            changed |= updateIfChanged(deliveryDetail::getLandmark, deliveryDetail::setLandmark, request.landmark());

        if (request.label() != null)
            changed |= updateIfChanged(deliveryDetail::getLabel, deliveryDetail::setLabel, request.label());

        // Handle default switch separately since it's a primitive boolean
        if (request.isDefault() && !Boolean.TRUE.equals(deliveryDetail.getIsDefault())) {
            deliveryDetailRepository.clearDefaultForUser(currentUser.getId());
            deliveryDetail.setIsDefault(true);
            changed = true;
        }

        if (changed) {
            deliveryDetail = deliveryDetailRepository.save(deliveryDetail);
        }

        return DeliveryDetailMapper.toDeliveryDetailResponse(deliveryDetail);
    }

    @Override
    public void removeDeliveryDetail(Long deliveryDetailId) {
        DeliveryDetail deliveryDetail = deliveryDetailRepository
                .findByIdAndUser(deliveryDetailId, authUserUtil.getCurrentUser())
                .orElseThrow(() -> new ResourceNotFoundException(ADDRESS_NOT_FOUND));
        deliveryDetailRepository.delete(deliveryDetail);
    }

    @Override
    public List<DeliveryDetailResponse> getMyDeliveryDetails() {
        return deliveryDetailRepository
                .findAllByUser(authUserUtil.getCurrentUser())
                .stream().map(DeliveryDetailMapper::toDeliveryDetailResponse)
                .toList();
    }

    @Transactional
    @Override
    public void setDefaultDeliveryDetail(Long deliveryDetailId) {
        User currentUser = authUserUtil.getCurrentUser();

        DeliveryDetail address = deliveryDetailRepository.findByIdAndUser(deliveryDetailId, currentUser)
                .orElseThrow(() -> new ResourceNotFoundException(ADDRESS_NOT_FOUND));

        // 1. clear all defaults first
        int cleared = deliveryDetailRepository.clearDefaultForUser(currentUser.getId());
        log.info("Cleared {} default addresses", cleared);

        // 2. flush to ensure the bulk update is written before the next save
        deliveryDetailRepository.flush();

        // 3. refresh the entity from DB to avoid stale cache
        address.setIsDefault(true);
        DeliveryDetail updatedAddress = deliveryDetailRepository.save(address);
        log.info("Address updated to default: {}", updatedAddress.getIsDefault());
    }

    // HELPER METHODS
    private <T> boolean updateIfChanged(Supplier<T> currentValue, Consumer<T> setter, T newValue) {
        if (Objects.equals(currentValue.get(), newValue)) return false;
        setter.accept(newValue);
        return true;
    }
}
