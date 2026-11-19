package com.example.perfume_budget.service;

import java.util.List;

/**
 * The cache names of the public (anonymous) catalog, in ONE place (SEC-03d N13). Every write that changes what a
 * storefront shows must evict all of {@link #PUBLIC_CATALOG}; a name typed twice is how one gets forgotten. The
 * constants are compile-time strings so annotations can use them:
 * {@code @CacheEvict(cacheNames = {CatalogCaches.PUBLIC_LISTINGS, CatalogCaches.PUBLIC_FEATURED, CatalogCaches.PRODUCT_DETAILS}, allEntries = true)}.
 */
public final class CatalogCaches {

    /** NEW names since SEC-03b: entries of the old (admin-shaped) DTO live under the old names and are never read. */
    public static final String PUBLIC_LISTINGS = "publicCustomerProductListings";
    public static final String PUBLIC_FEATURED = "publicFeaturedProducts";
    public static final String PRODUCT_DETAILS = "productDetailsPage";

    public static final List<String> PUBLIC_CATALOG = List.of(PUBLIC_LISTINGS, PUBLIC_FEATURED, PRODUCT_DETAILS);

    private CatalogCaches() {
    }
}
