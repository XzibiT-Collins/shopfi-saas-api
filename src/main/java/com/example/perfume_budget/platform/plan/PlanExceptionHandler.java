package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.dto.CustomApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps {@link PlanValidationException} to the standard bean-validation 400 shape (plans.md section 1). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlanExceptionHandler {

    @ExceptionHandler(PlanValidationException.class)
    public ResponseEntity<CustomApiResponse<Map<String, String>>> planValidation(PlanValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Validation failed", ex.fields()));
    }
}
