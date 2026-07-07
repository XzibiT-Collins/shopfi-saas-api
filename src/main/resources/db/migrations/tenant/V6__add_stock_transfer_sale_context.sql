-- Sale-context columns on the physical transfer ledger.
-- Lets the items-sold report attribute each deduction to its order (reference_*) and
-- show the per-location balance at the moment of the move (balance_after).
-- All nullable: historical rows pre-dating this migration stay null (cannot be reconstructed).

ALTER TABLE stock_transfers
    ADD COLUMN balance_after INTEGER;

ALTER TABLE stock_transfers
    ADD COLUMN reference_type VARCHAR(32);

ALTER TABLE stock_transfers
    ADD COLUMN reference_id VARCHAR(255);

CREATE INDEX idx_stock_transfers_ref ON stock_transfers (reference_type, reference_id);
