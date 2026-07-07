package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.dto.tax.request.TaxRequest;
import com.example.perfume_budget.dto.tax.response.TaxAndDiscountCalculationResponse;
import com.example.perfume_budget.dto.tax.response.TaxResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.OrderTaxMapper;
import com.example.perfume_budget.mapper.TaxMapper;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.OrderTax;
import com.example.perfume_budget.model.Tax;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.repository.TaxRepository;
import com.example.perfume_budget.service.interfaces.OrderService;
import com.example.perfume_budget.service.interfaces.TaxService;
import com.example.perfume_budget.utils.DiscountCalculationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaxServiceImpl implements TaxService {

    private final TaxRepository taxRepository;
    private final DiscountCalculationUtil discountCalculationUtil;
    private final CouponRepository couponRepository;
    private static final String INVALID_COUPON_CODE = "Invalid coupon code.";



    @Override
    @Transactional
    public TaxResponse createTax(TaxRequest request) {
        if (taxRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("Tax already exists with name: " + request.name());
        }
        if (taxRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException("Tax already exists with code: " + request.code());
        }
        Tax tax = TaxMapper.toTax(request);
        return TaxMapper.toTaxResponse(taxRepository.save(tax));
    }

    @Override
    @Transactional
    public TaxResponse updateTax(Long id, TaxRequest request) {
        Tax tax = taxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tax not found with id: " + id));

        if (!tax.getName().equals(request.name()) && taxRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("Tax already exists with name: " + request.name());
        }
        if (!tax.getCode().equals(request.code()) && taxRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException("Tax already exists with code: " + request.code());
        }

        TaxMapper.updateTax(tax, request);
        return TaxMapper.toTaxResponse(taxRepository.save(tax));
    }

    @Override
    public TaxResponse getTaxById(Long id) {
        return taxRepository.findById(id)
                .map(TaxMapper::toTaxResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Tax not found with id: " + id));
    }

    @Override
    public List<TaxResponse> getAllTaxes() {
        return taxRepository.findAll().stream()
                .map(TaxMapper::toTaxResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteTax(Long id) {
        if (!taxRepository.existsById(id)) {
            throw new ResourceNotFoundException("Tax not found with id: " + id);
        }
        taxRepository.deleteById(id);
    }

    @Override
    public TaxCalculationResult calculateTaxes(BigDecimal subtotal) {
        List<Tax> activeTaxes = taxRepository.findAllByIsActiveTrueOrderByApplyOrderAsc();

        List<OrderTax> orderTaxes = new ArrayList<>();
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        BigDecimal runningAmount = subtotal;

        for (Tax tax : activeTaxes) {
            BigDecimal taxableAmount = Boolean.TRUE.equals(tax.getIsCompound()) ? runningAmount : subtotal;
            BigDecimal taxAmount = taxableAmount
                    .multiply(tax.getRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            orderTaxes.add(OrderTax.builder()
                    .taxName(tax.getName())
                    .taxCode(tax.getCode())
                    .taxRate(tax.getRate())
                    .taxableAmount(new Money(taxableAmount, CurrencyCode.GHS))
                    .taxAmount(new Money(taxAmount, CurrencyCode.GHS))
                    .build());

            totalTaxAmount = totalTaxAmount.add(taxAmount);
            runningAmount = runningAmount.add(taxAmount);
        }

        return TaxCalculationResult.builder()
                .orderTaxes(orderTaxes)
                .totalTaxAmount(new Money(totalTaxAmount, CurrencyCode.GHS))
                .totalAmountAfterTax(new Money(runningAmount, CurrencyCode.GHS))
                .build();
    }

    @Override
    public TaxAndDiscountCalculationResponse calculateTaxesAtCheckout(BigDecimal subtotal, String couponCode) {
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            Coupon coupon = couponRepository.findByCode(couponCode.strip().toUpperCase())
                    .orElseThrow(() -> new BadRequestException(INVALID_COUPON_CODE));
            discountCalculationUtil.checkIfCouponIsValid(coupon);
            discount = discountCalculationUtil.calculateDiscount(coupon, subtotal);
            subtotal = subtotal.subtract(discount);
        }
        TaxCalculationResult result = calculateTaxes(subtotal);

        return TaxAndDiscountCalculationResponse.builder()
                .orderTaxes(result.orderTaxes().stream().map(OrderTaxMapper::toOrderTaxResponse).toList())
                .totalTaxAmount(result.totalTaxAmount().toString())
                .totalAmountAfterTax(result.totalAmountAfterTax().toString())
                .discount(discount)
                .build();
    }
}
