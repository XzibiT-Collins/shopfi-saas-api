-- WALKIN-BE (API-RD-03 AC2): idempotent walk-in sales. One walk-in order per (cashier, Idempotency-Key) per tenant
-- (the tenant is the schema). The fingerprint is a SHA-256 of the normalised request body, so a key reused for a
-- different body is detected. Both columns stay on the order for its whole life: a later cancel / refund (AC3) can
-- still be matched to the sale, and a replay after a cancel answers with the order's current status.
-- Additive only; keyless sales keep NULLs.
ALTER TABLE walk_in_orders ADD COLUMN idempotency_key VARCHAR(64);
ALTER TABLE walk_in_orders ADD COLUMN request_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX uq_walk_in_orders_user_idempotency_key
    ON walk_in_orders (processed_by_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
