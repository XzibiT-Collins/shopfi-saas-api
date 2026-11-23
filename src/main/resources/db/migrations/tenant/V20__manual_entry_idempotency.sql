-- API-RD-12d: idempotent manual journal entries. One manual entry per (admin, Idempotency-Key) per tenant (the tenant
-- is the schema). The fingerprint is a SHA-256 of the normalised request body, so a key reused for a different entry is
-- detected; the body itself is not kept. Additive only; automatic entries and keyless manual entries keep NULLs.
-- Re-runnable (IF NOT EXISTS) like V18, because tests and the platform reactivate path replay the newest migrations.
ALTER TABLE journal_entries ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);
ALTER TABLE journal_entries ADD COLUMN IF NOT EXISTS idempotency_user_id BIGINT;
ALTER TABLE journal_entries ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_journal_entries_user_idempotency_key
    ON journal_entries (idempotency_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
