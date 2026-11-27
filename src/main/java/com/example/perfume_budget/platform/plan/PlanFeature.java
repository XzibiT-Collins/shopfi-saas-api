package com.example.perfume_budget.platform.plan;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The feature catalog (docs/api/plans.md section 2). The CODE defines which features exist (owner decision); which plan
 * has which feature or limit is data ({@code master.plan_entitlements}). Declaration order is the catalog order every
 * response uses; {@code group} is display text and groups appear in the order of their first feature. 16 keys: owner O3
 * dropped {@code MAX_CUSTOM_DOMAINS}, the PO dropped {@code RETURNS_REFUNDS} (returns and refunds are core).
 *
 * <p>Labels and groups change with a release, never through the API. Enforcement (which routes each key gates) is PLAN-02.
 */
public enum PlanFeature {
    CUSTOM_DOMAIN(FeatureKind.BOOLEAN, "Custom domain", "Storefront"),
    THEME_ADVANCED(FeatureKind.BOOLEAN, "Advanced theme customisation", "Storefront"),
    MAX_PRODUCTS(FeatureKind.LIMIT, "Products", "Catalog"),
    PRODUCT_FAMILIES(FeatureKind.BOOLEAN, "Product families and pack sizes", "Catalog"),
    WALK_IN_POS(FeatureKind.BOOLEAN, "Walk-in point of sale", "Orders"),
    SHOP_WIDE_DISCOUNTS(FeatureKind.BOOLEAN, "Shop-wide discounts", "Marketing"),
    MAX_ACTIVE_COUPONS(FeatureKind.LIMIT, "Active coupons", "Marketing"),
    INVENTORY_MANAGEMENT(FeatureKind.BOOLEAN, "Inventory adjustments and history", "Inventory"),
    STOCK_TRANSFERS(FeatureKind.BOOLEAN, "Stock transfers between locations", "Inventory"),
    STOCK_CONVERSION(FeatureKind.BOOLEAN, "Stock conversion", "Inventory"),
    MAX_LOCATIONS(FeatureKind.LIMIT, "Storage locations", "Inventory"),
    SALES_REPORTS(FeatureKind.BOOLEAN, "Sales reports", "Finance and reports"),
    TAX_CONFIG(FeatureKind.BOOLEAN, "Tax configuration", "Finance and reports"),
    ACCOUNTING(FeatureKind.BOOLEAN, "Accounting and bookkeeping", "Finance and reports"),
    MAX_STAFF(FeatureKind.LIMIT, "Front-desk staff accounts", "Team and security"),
    LOGIN_OTP(FeatureKind.BOOLEAN, "Login one-time codes", "Team and security");

    private static final Map<String, PlanFeature> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private final FeatureKind kind;
    private final String label;
    private final String group;

    PlanFeature(FeatureKind kind, String label, String group) {
        this.kind = kind;
        this.label = label;
        this.group = group;
    }

    public FeatureKind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public String group() {
        return group;
    }

    /** The feature for a stored or submitted key; empty for a key the code does not know (never {@code valueOf}). */
    public static Optional<PlanFeature> fromKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(key));
    }
}
