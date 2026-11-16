-- API-04 rework. Additive data fixes for coupons; no applied migration is edited.

-- 1. Coupon codes are case-insensitive: every code is stored trimmed and UPPER-CASED (what checkout looks up).
--    Legacy rows created before API-04 may be lower/mixed case or blank. Collision rule (never fails the migration
--    for an ordinary collision, never deletes a row):
--      * among codes that are equal after trim + upper-case, the row whose code is ALREADY exactly upper-case wins
--        (it is the one checkout has always found), otherwise the lowest id wins; the winner gets the normalised code;
--      * every other row of the group is renamed to <NORMALISED>-DUP-<id> and deactivated (it could never be redeemed:
--        checkout always resolved the normalised code to the winner);
--      * a blank code becomes LEGACY-BLANK-<id> and is deactivated (it could never be redeemed either).
--    Orders keep pointing at the same coupon rows (by id). If a renamed value happened to collide with an existing
--    code the unique index below makes the migration fail loudly instead of guessing.
ALTER TABLE coupons DROP CONSTRAINT IF EXISTS uc_coupons_code;

WITH ranked AS (
    SELECT id,
           upper(btrim(code, E' \t\r\n')) AS norm,
           row_number() OVER (PARTITION BY upper(btrim(code, E' \t\r\n'))
                              ORDER BY (code = upper(btrim(code, E' \t\r\n'))) DESC, id) AS rk
    FROM coupons
), target AS (
    SELECT id,
           CASE WHEN norm = '' THEN 'LEGACY-BLANK-' || id
                WHEN rk = 1 THEN norm
                ELSE norm || '-DUP-' || id END AS new_code,
           (norm = '' OR rk > 1) AS demote
    FROM ranked
)
UPDATE coupons c
SET code      = t.new_code,
    is_active = CASE WHEN t.demote THEN FALSE ELSE c.is_active END
FROM target t
WHERE t.id = c.id
  AND (c.code IS DISTINCT FROM t.new_code OR t.demote);

ALTER TABLE coupons ADD CONSTRAINT uc_coupons_code UNIQUE (code);
-- Case-insensitive uniqueness: LEGACY10 can no longer be created next to legacy10.
CREATE UNIQUE INDEX uq_coupons_code_upper ON coupons (upper(code));

-- 2. usage_count is the number of LIVE redemptions (in-flight checkouts + orders that are not CANCELLED/FAILED).
UPDATE coupons SET usage_count = 0 WHERE usage_count IS NULL;
ALTER TABLE coupons ALTER COLUMN usage_count SET DEFAULT 0;

-- true only while the coupon is inactive BECAUSE a redemption reached usage_limit (set by checkout). A release that
-- brings usage_count back under the limit re-activates such a coupon; an admin deactivate / edit clears the flag so
-- a coupon an admin switched off is never switched back on by a release.
ALTER TABLE coupons ADD COLUMN limit_deactivated BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. Each order holds at most one redemption of its coupon; it is released exactly once when the order is CANCELLED,
--    FAILED or expires (and taken again if a late payment revives the order).
ALTER TABLE orders ADD COLUMN coupon_redemption_released BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: orders already CANCELLED/FAILED release their redemption now. Coupons that were auto-deactivated at the
-- limit before this migration are NOT re-activated (the reason was not recorded); an admin can switch them back on.
WITH released AS (
    UPDATE orders SET coupon_redemption_released = TRUE
    WHERE coupon_id IS NOT NULL AND status IN ('CANCELLED', 'FAILED')
    RETURNING coupon_id
), per_coupon AS (
    SELECT coupon_id, count(*) AS n FROM released GROUP BY coupon_id
)
UPDATE coupons c
SET usage_count = GREATEST(c.usage_count - p.n, 0)
FROM per_coupon p
WHERE c.id = p.coupon_id;

-- Live redemptions per coupon / per customer (per-user limit, delete check).
CREATE INDEX idx_orders_coupon_live ON orders (coupon_id, user_id)
    WHERE coupon_id IS NOT NULL AND NOT coupon_redemption_released;
