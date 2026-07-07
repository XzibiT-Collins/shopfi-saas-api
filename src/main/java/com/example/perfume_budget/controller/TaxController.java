package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import com.example.perfume_budget.dto.tax.request.TaxRequest;
import com.example.perfume_budget.dto.tax.response.TaxAndDiscountCalculationResponse;
import com.example.perfume_budget.dto.tax.response.TaxResponse;
import com.example.perfume_budget.service.interfaces.TaxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tax")
@RequiredArgsConstructor
public class TaxController {
    private final TaxService taxService;

    @GetMapping
    public ResponseEntity<CustomApiResponse<TaxAndDiscountCalculationResponse>> calculateTaxes(
            @RequestParam(name = "subtotal") BigDecimal subtotal,
            @RequestParam(name = "couponCode", defaultValue = "") String couponCode) {
        return ResponseEntity.ok().body(
                CustomApiResponse.success(
                        taxService.calculateTaxesAtCheckout(subtotal, couponCode)
                )
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/add-tax")
    public ResponseEntity<CustomApiResponse<TaxResponse>> addTax(@Valid @RequestBody TaxRequest request) {
        return ResponseEntity.ok().body(
                CustomApiResponse.success(taxService.createTax(request))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/update/{taxId}")
    public ResponseEntity<CustomApiResponse<TaxResponse>> updateTax(@PathVariable Long taxId, @Valid @RequestBody TaxRequest request) {
        return ResponseEntity.ok().body(
                CustomApiResponse.success(taxService.updateTax(taxId, request))
        );
    }

    @GetMapping("/all")
    public ResponseEntity<CustomApiResponse<List<TaxResponse>>> getAllTaxes() {
        return ResponseEntity.ok().body(
                CustomApiResponse.success(taxService.getAllTaxes())
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{taxId}")
    public ResponseEntity<CustomApiResponse<TaxResponse>> getTaxById(@PathVariable Long taxId) {
        return ResponseEntity.ok().body(
                CustomApiResponse.success(taxService.getTaxById(taxId))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/delete/{taxId}")
    public ResponseEntity<Void> deleteTax(@PathVariable Long taxId) {
        taxService.deleteTax(taxId);
        return ResponseEntity.noContent().build();
    }
}
