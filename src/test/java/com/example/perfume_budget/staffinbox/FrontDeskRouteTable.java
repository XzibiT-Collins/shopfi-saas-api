package com.example.perfume_budget.staffinbox;

import java.util.List;
import java.util.Map;

/**
 * The permission to route table of docs/api/auth-me.md, as code. Used twice: AuthMeDocContractTest parses the doc table and
 * requires it to equal this map; StaffInboxAndAuthMeIntegrationTest proves every route here answers 403 to a front-desk
 * user holding every OTHER permission and not 403 to one holding only this permission. Doc and behaviour therefore meet here.
 */
public final class FrontDeskRouteTable {
    private FrontDeskRouteTable() {
    }

    public static final Map<String, List<String>> ROUTES = Map.of(
            "WALK_IN_ORDER_CREATE", List.of("POST /admin/walk-in/quote", "POST /admin/walk-in/order"),
            "CUSTOMER_SEARCH", List.of("GET /admin/walk-in/customers/search", "POST /admin/walk-in/customers/search"),
            "WALK_IN_ORDER_VIEW", List.of("GET /admin/walk-in/orders", "GET /admin/walk-in/orders/{orderNumber}"),
            "WALK_IN_ORDER_MARK_RECEIPT_PRINTED", List.of("PATCH /admin/walk-in/orders/{orderNumber}/receipt-printed"),
            "STOCK_TRANSFER", List.of("POST /admin/inventory/transfers", "GET /admin/inventory/transfers"),
            "STOCK_CONVERSION", List.of("POST /admin/stock-conversions/forward", "POST /admin/stock-conversions/reverse",
                    "GET /admin/stock-conversions/reverse/{id}/variants"),
            "PRODUCT_VIEW_ADMIN_CATALOG", List.of(),
            "PRODUCT_VIEW_STOCK_SUMMARY", List.of());
}
