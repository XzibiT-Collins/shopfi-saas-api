-- ORD-02: follow-ups of the ORD-01 review. Additive only (no applied migration is edited).

-- 1. Where a consumed e-commerce allocation physically left the shop (the location-ledger SALE_DEDUCTION), so a later
--    return (paid cancel, return received) puts the units back at THAT location even if the e-commerce fulfilment
--    source was moved in between.
--    fulfilment_location_recorded = TRUE: the deduction was recorded by ORD-02+ code (or found by the backfill below);
--    fulfilment_location_id NULL with TRUE means "no location was configured, nothing was deducted, nothing to return".
--    FALSE (legacy rows the backfill could not match): the return falls back to the current fulfilment source, which
--    is the pre-ORD-02 behaviour.
ALTER TABLE inventory_allocations ADD COLUMN fulfilment_location_id BIGINT;
ALTER TABLE inventory_allocations ADD COLUMN fulfilment_location_recorded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE inventory_allocations
    ADD CONSTRAINT fk_inventory_alloc_fulfilment_location FOREIGN KEY (fulfilment_location_id) REFERENCES storage_locations (id);

-- Backfill: the ledger has recorded the order's deduction per (order, product) since V6 (reference_type/reference_id);
-- the newest SALE_DEDUCTION wins. Older deductions (before V6) carry no reference and stay unmatched (legacy rule).
UPDATE inventory_allocations a
SET fulfilment_location_id       = d.from_location_id,
    fulfilment_location_recorded = TRUE
FROM (SELECT DISTINCT ON (t.reference_id, t.product_id) t.reference_id, t.product_id, t.from_location_id
      FROM stock_transfers t
      WHERE t.reference_type = 'ORDER'
        AND t.transfer_type = 'SALE_DEDUCTION'
        AND t.from_location_id IS NOT NULL
      ORDER BY t.reference_id, t.product_id, t.id DESC) d
WHERE a.reference_type = 'ORDER'
  AND a.status IN ('CONSUMED', 'RETURNED')
  AND a.reference_id = d.reference_id
  AND a.product_id = d.product_id;

-- 2. order_audit_events is append-only. ORD-01 declared it so but the order FK was ON DELETE CASCADE, which would let
--    an order delete silently erase its history. Orders are never deleted by the application: RESTRICT makes such a
--    delete fail loudly instead. Row triggers reject UPDATE and DELETE of audit rows. TRUNCATE stays possible on
--    purpose (a deliberate whole-table operator/test reset, e.g. TRUNCATE orders ... CASCADE), and DROP SCHEMA ...
--    CASCADE (tenant drop / provisioning compensation) fires no row trigger.
ALTER TABLE order_audit_events DROP CONSTRAINT fk_order_audit_events_order;
-- Added NOT VALID and then validated (the standard online-FK pattern: the VALIDATE scan itself only needs SHARE UPDATE
-- EXCLUSIVE). Note: Flyway runs this file in one transaction and the DROP CONSTRAINT above already holds ACCESS
-- EXCLUSIVE on order_audit_events until commit, so the gain here is limited to not holding the scan under the ADD.
ALTER TABLE order_audit_events
    ADD CONSTRAINT fk_order_audit_events_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE order_audit_events VALIDATE CONSTRAINT fk_order_audit_events_order;

CREATE FUNCTION order_audit_events_immutable() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'order_audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON order_audit_events
    FOR EACH ROW EXECUTE FUNCTION order_audit_events_immutable();
