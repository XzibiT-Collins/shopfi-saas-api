package com.example.perfume_budget.service;

import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantLinkBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@code callback_url} sent to Paystack when initialising a payment: the CURRENT tenant's storefront origin from
 * {@link TenantLinkBuilder} (verified custom domain, else {@code <slug>.<base-domain>}) plus a fixed path. The tenant
 * comes from {@link TenantContext} (resolved by the tenant filter), never from a request header/param, so no client
 * controlled value can reach the URL. Empty when no origin can be derived (dev without a base domain): the field is
 * then omitted and Paystack falls back to the callback configured in the dashboard.
 */
@Component
public class CheckoutCallbackUrlProvider {

    private final TenantLinkBuilder linkBuilder;
    private final String callbackPath;

    public CheckoutCallbackUrlProvider(TenantLinkBuilder linkBuilder,
                                       @Value("${checkout.callback-path:/checkout/callback}") String callbackPath) {
        this.linkBuilder = linkBuilder;
        String p = callbackPath == null || callbackPath.isBlank() ? "/checkout/callback" : callbackPath.strip();
        this.callbackPath = p.startsWith("/") ? p : "/" + p;
    }

    public Optional<String> callbackUrl() {
        return linkBuilder.origin(TenantContext.getSlug()).map(origin -> origin + callbackPath);
    }
}
