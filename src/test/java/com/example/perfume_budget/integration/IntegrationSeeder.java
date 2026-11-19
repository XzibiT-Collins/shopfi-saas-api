package com.example.perfume_budget.integration;

import com.example.perfume_budget.dto.payment.request.PaymentSettingsRequest;
import com.example.perfume_budget.service.interfaces.PaymentSettingsService;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Seeds the two fixture tenants the real-stack suites run against: acme and bravo, each with an admin
 * (admin@&lt;slug&gt;.test), a verified customer (customer@&lt;slug&gt;.test) with a default delivery address, categories,
 * products with distinct prices / creation times (so sort assertions are meaningful), a coupon and a FAKE Paystack
 * secret. Everything is fixed and public: these are fixtures, never real credentials. The default theme needs no
 * seed (GET /api/v1/theme serves the contract defaults until a PUT).
 */
final class IntegrationSeeder {

    static final String CUSTOMER_PASSWORD = "Passw0rd!cust";
    static final String COUPON_CODE = "SAVE10";

    private IntegrationSeeder() {
    }

    static String paystackSecret(String slug) {
        return "sk_test_FAKE_integration_" + slug;
    }

    static void seed(TestTenants tenants, JdbcTemplate jdbc, PasswordEncoder encoder, PaymentSettingsService paymentSettings) {
        for (String slug : List.of("acme", "bravo")) {
            TestTenant t = tenants.ensureTenant(slug);
            String s = t.schemaName();
            if (jdbc.queryForObject("SELECT count(*) FROM " + s + ".categories", Integer.class) > 0) {
                continue; // already seeded
            }
            String cap = Character.toUpperCase(slug.charAt(0)) + slug.substring(1);
            long fragrances = category(jdbc, s, cap + " Fragrances", slug + "-fragrances");
            long skincare = category(jdbc, s, cap + " Skincare", slug + "-skincare");
            category(jdbc, s, cap + " Gifts", slug + "-gifts");

            // name, category, price, stock, minutes ago created, featured
            product(jdbc, s, cap + " Rose Eau", fragrances, "120.00", 50, 600, true);
            product(jdbc, s, cap + " Amber Noir", fragrances, "250.00", 50, 500, true);
            product(jdbc, s, cap + " Citrus Mist", fragrances, "75.50", 50, 400, false);
            product(jdbc, s, cap + " Velvet Cream", skincare, "45.00", 50, 300, false);
            product(jdbc, s, cap + " Oud Royale", fragrances, "399.99", 50, 200, true);
            product(jdbc, s, cap + " Night Serum", skincare, "180.00", 50, 100, false);
            product(jdbc, s, cap + " Sold Out Mist", skincare, "20.00", 0, 50, false);
            jdbc.update("INSERT INTO " + s + ".coupons (code, discount_type, discount_value, is_active, start_date, expiration_date, "
                    + "usage_count, usage_limit, created_at) VALUES (?, 'PERCENTAGE', 10, true, current_date - 1, current_date + 365, 0, 1000, now())",
                    COUPON_CODE);

            customer(jdbc, s, encoder, "customer@" + slug + ".test", cap + " Customer");

            tenants.runAs(t, () -> paymentSettings.save(
                    new PaymentSettingsRequest(paystackSecret(slug), "pk_test_FAKE_integration_" + slug, true)));
        }
    }

    private static long category(JdbcTemplate jdbc, String s, String name, String slug) {
        return jdbc.queryForObject("INSERT INTO " + s + ".categories (name, slug, description, created_at) VALUES (?, ?, ?, now()) RETURNING id",
                Long.class, name, slug, name + " (fixture)");
    }

    private static void product(JdbcTemplate jdbc, String s, String name, long categoryId, String price, int stock,
                                int minutesAgo, boolean featured) {
        String slug = name.toLowerCase().replace(' ', '-');
        jdbc.update("INSERT INTO " + s + ".products (version, name, slug, sku, short_description, description, stock_quantity, sold_count, "
                        + "category_id, is_active, is_enlisted, is_featured, view_count, price_amount, price_currency, created_at) "
                        + "VALUES (0, ?, ?, ?, ?, ?, ?, 0, ?, true, true, ?, 0, ?, 'GHS', ?)",
                name, slug, slug.toUpperCase(), name + " short", name + " long description", stock, categoryId, featured,
                new BigDecimal(price), LocalDateTime.now().minusMinutes(minutesAgo));
    }

    private static void customer(JdbcTemplate jdbc, String s, PasswordEncoder encoder, String email, String fullName) {
        Long id = jdbc.queryForObject("INSERT INTO " + s + ".users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES (?, ?, ?, 'CUSTOMER', true, true, now()) RETURNING id", Long.class, fullName, email, encoder.encode(CUSTOMER_PASSWORD));
        jdbc.update("INSERT INTO " + s + ".delivery_details (user_id, recipient_name, phone_number, address_line1, city, region, label, is_default, created_at) "
                + "VALUES (?, ?, '0200000000', '1 Main St', 'Accra', 'Greater Accra', 'HOME', true, now())", id, fullName);
    }
}
