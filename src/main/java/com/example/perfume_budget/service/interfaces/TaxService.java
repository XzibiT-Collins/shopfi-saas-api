package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResult;
import com.example.perfume_budget.dto.tax.request.TaxRequest;
import com.example.perfume_budget.dto.tax.response.TaxAndDiscountCalculationResponse;
import com.example.perfume_budget.dto.tax.response.TaxResponse;

import java.math.BigDecimal;
import java.util.List;

public interface TaxService {
    TaxResponse createTax(TaxRequest request);
    TaxResponse updateTax(Long id, TaxRequest request);
    TaxResponse getTaxById(Long id);
    List<TaxResponse> getAllTaxes();
    void deleteTax(Long id);

    TaxCalculationResult calculateTaxes(BigDecimal subtotal);

    TaxAndDiscountCalculationResponse calculateTaxesAtCheckout(BigDecimal subtotal, String couponCode);
}
