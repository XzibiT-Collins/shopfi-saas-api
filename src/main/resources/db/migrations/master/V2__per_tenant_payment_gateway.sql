-- Per-tenant Paystack accounts: routing is by a per-tenant webhook token, not by a
-- reference->tenant route table. Drop the obsolete table and add the token to tenants.
DROP TABLE IF EXISTS payment_tenant_routes;

ALTER TABLE tenants
    ADD COLUMN webhook_token VARCHAR(64);

ALTER TABLE tenants
    ADD CONSTRAINT uq_tenants_webhook_token UNIQUE (webhook_token);
