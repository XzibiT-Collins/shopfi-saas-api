package com.example.perfume_budget.controller;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.entitlement.RequiresFeature;
import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.accounts.request.ManualJournalEntryRequest;
import com.example.perfume_budget.service.BookkeepingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/admin/bookkeeping")
@RequiredArgsConstructor
public class BookkeepingController {
    private final BookkeepingService bookkeepingService;

    @RequiresFeature(PlanFeature.ACCOUNTING)
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/manual-entry")
    public ResponseEntity<CustomApiResponse<Void>> recordManualEntry(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ManualJournalEntryRequest request) {

        boolean replay = bookkeepingService.recordManualEntry(request, idempotencyKey);
        // the body stays {description:null, data:null} as before; a replay is signalled by a header only
        return ResponseEntity.ok().header("Idempotency-Replayed", String.valueOf(replay))
                .body(CustomApiResponse.success(null));
    }
}
