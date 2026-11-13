package com.example.perfume_budget.support;

/** A tenant created by {@link TestTenants}: public slug, physical schema and its seeded admin credentials. */
public record TestTenant(String slug, String schemaName, String adminEmail, String adminPassword) {
}
