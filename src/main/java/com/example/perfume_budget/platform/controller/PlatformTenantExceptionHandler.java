package com.example.perfume_budget.platform.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.platform.service.IllegalTenantTransitionException;
import com.example.perfume_budget.platform.service.TenantProvisioningFailedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps PLAT-02 errors for this controller only (the global handler is left untouched). No internals in bodies. */
@RestControllerAdvice(assignableTypes = PlatformTenantController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformTenantExceptionHandler {

    @ExceptionHandler(IllegalTenantTransitionException.class)
    public ResponseEntity<CustomApiResponse<String>> illegalTransition(IllegalTenantTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(TenantProvisioningFailedException.class)
    public ResponseEntity<CustomApiResponse<String>> provisioningFailed(TenantProvisioningFailedException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CustomApiResponse.error(ex.getMessage()));
    }
}
