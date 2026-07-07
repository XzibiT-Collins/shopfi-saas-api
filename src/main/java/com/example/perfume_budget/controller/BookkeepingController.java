package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.accounts.request.ManualJournalEntryRequest;
import com.example.perfume_budget.service.BookkeepingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/admin/bookkeeping")
@RequiredArgsConstructor
public class BookkeepingController {
    private final BookkeepingService bookkeepingService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/manual-entry")
    public ResponseEntity<CustomApiResponse<Void>> recordManualEntry(
            @Valid @RequestBody ManualJournalEntryRequest request) {

        bookkeepingService.recordManualEntry(request);
        return ResponseEntity.ok(CustomApiResponse.success(null));
    }
}
