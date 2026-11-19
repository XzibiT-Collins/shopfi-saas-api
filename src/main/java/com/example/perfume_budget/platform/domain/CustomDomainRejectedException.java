package com.example.perfume_budget.platform.domain;

import com.example.perfume_budget.exception.ApiCodeException;
import org.springframework.http.HttpStatus;

/**
 * A custom domain that fails {@link CustomDomains} validation: {@code 400} with a stable {@code data.code}
 * (docs/api/custom-domains.md). The message never echoes the submitted value.
 */
public class CustomDomainRejectedException extends ApiCodeException {

    public CustomDomainRejectedException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
