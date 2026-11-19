-- ORD-07: returns of DELIVERED orders. Additive only (no applied migration is edited).
--
-- delivered_at: when the order was marked DELIVERED; the optional return window (shopfi.orders.return-window-days)
-- counts from it. returned_at / returned_by: when and by which admin (e-mail only) the return was recorded.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS returned_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS returned_by VARCHAR(255);

-- Back-fill delivered_at of orders that are already DELIVERED: the newest audit row that moved the order to DELIVERED
-- (ORD-01+), else the order's last update (a delivered order is final, so that was the delivery or later), else its
-- creation. Idempotent: only rows still without a value are touched.
UPDATE orders o
SET delivered_at = COALESCE(
        (SELECT max(e.created_at) FROM order_audit_events e
         WHERE e.order_id = o.id AND e.event_type = 'STATUS_CHANGED' AND e.to_status = 'DELIVERED'),
        o.updated_at,
        o.created_at)
WHERE o.delivery_status = 'DELIVERED'
  AND o.delivered_at IS NULL;
