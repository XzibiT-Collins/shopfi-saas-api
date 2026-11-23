package com.example.perfume_budget.handlers;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.exception.AccountingException;
import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** API-RD-13(a): an AccountingException used to answer 200 with an empty body. */
class AccountingExceptionHandlerTest {

    @Test
    void answers500WithAStableCodeAndNoLedgerDetail() {
        ResponseEntity<CustomApiResponse<Object>> r = new GlobalExceptionHandler()
                .handleAccountingException(new AccountingException("Account not found for category: SALES_REVENUE Debits: 12.00"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().description()).isEqualTo(GlobalExceptionHandler.ACCOUNTING_FAILED)
                .doesNotContain("SALES_REVENUE").doesNotContain("12.00");
        assertThat(r.getBody().data()).isEqualTo(Map.of("code", "ACCOUNTING_ERROR"));
    }
}
