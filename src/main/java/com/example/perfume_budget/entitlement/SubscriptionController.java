package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.dto.CustomApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PLAN-02: the caller's own tenant subscription (docs/api/plans.md 13). The tenant is never a parameter. */
@RestController
@RequestMapping("/api/v1/admin/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PreAuthorize("hasAnyRole('ADMIN','FRONT_DESK')")
    @GetMapping
    public ResponseEntity<CustomApiResponse<SubscriptionView>> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CustomApiResponse.success(subscriptionService.current()));
    }
}
