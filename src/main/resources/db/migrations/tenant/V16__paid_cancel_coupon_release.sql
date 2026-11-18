-- ORD-02b. Additive data fix; no applied migration is edited.
--
-- PO decision 2026-11-18: a PAID order the merchant cancels gives its coupon redemption back (coupons.usage_count and
-- the customer's per-user count, which ignores orders whose redemption is released). The application now releases it
-- at the cancel. Paid orders cancelled before this release (ORD-02: status COMPLETED, delivery_status CANCELLED) still
-- hold theirs: release each of them exactly once here, the same way V12 did for CANCELLED / FAILED orders.
--
-- Re-activation follows CouponRepository#releaseRedemption: a coupon that is inactive ONLY because it reached its
-- usage limit (limit_deactivated) is switched back on when the count drops under the limit; a coupon an admin switched
-- off stays off. Idempotent: an order already released is not selected again. Orders with a NULL delivery status
-- (legacy rows) are not cancelled and keep their redemption.
WITH released AS (
    UPDATE orders
    SET coupon_redemption_released = TRUE
    WHERE coupon_id IS NOT NULL
      AND NOT coupon_redemption_released
      AND status = 'COMPLETED'
      AND delivery_status = 'CANCELLED'
    RETURNING coupon_id
), per_coupon AS (
    SELECT coupon_id, count(*) AS n FROM released GROUP BY coupon_id
)
UPDATE coupons c
SET usage_count       = GREATEST(COALESCE(c.usage_count, 0) - p.n, 0),
    is_active         = CASE WHEN c.limit_deactivated AND GREATEST(COALESCE(c.usage_count, 0) - p.n, 0) < c.usage_limit
                             THEN TRUE ELSE c.is_active END,
    limit_deactivated = CASE WHEN c.limit_deactivated AND GREATEST(COALESCE(c.usage_count, 0) - p.n, 0) < c.usage_limit
                             THEN FALSE ELSE c.limit_deactivated END
FROM per_coupon p
WHERE c.id = p.coupon_id;
