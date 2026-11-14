-- PAY-01: checkout idempotency, explicit delivery detail, replayable payment initialisation,
-- throttled provider verification and refund flagging. Additive only (no applied migration is edited).

-- Idempotent checkout: one order per (customer, Idempotency-Key). The fingerprint is a SHA-256 of the
-- normalised request (cart lines + coupon + delivery detail) so a key reused for a different request is detected.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(64);
ALTER TABLE orders ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE orders ADD COLUMN delivery_detail_id BIGINT;
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_delivery_detail FOREIGN KEY (delivery_detail_id)
        REFERENCES delivery_details (id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_orders_user_idempotency_key
    ON orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Reuse lookup for a checkout without a key: the customer's open order for the same request.
CREATE INDEX idx_orders_user_open_fingerprint
    ON orders (user_id, request_fingerprint)
    WHERE status IN ('PENDING', 'INITIATED');

-- Webhook / verify lookups by provider reference.
CREATE INDEX idx_payments_order_id ON payments (order_id);

-- Stored so an idempotent replay returns the same Paystack authorization_url without a second initialise call.
ALTER TABLE payments ADD COLUMN authorization_url VARCHAR(512);
ALTER TABLE payments ADD COLUMN access_code VARCHAR(128);
-- Provider verification throttle (at most one provider call per payment per interval).
ALTER TABLE payments ADD COLUMN last_provider_check_at TIMESTAMP WITHOUT TIME ZONE;
-- A signed success arrived for an order that could no longer be fulfilled (cancelled/expired and out of stock):
-- the customer paid, so the payment is COMPLETED and this flag marks it for a manual refund.
ALTER TABLE payments ADD COLUMN refund_required BOOLEAN NOT NULL DEFAULT FALSE;
