-- PLAT-02b: distinguish the rows written around one platform action. The table stays append-only (V6 triggers are
-- untouched; ADD COLUMN with a constant default rewrites nothing and fires no row trigger), so an action that spans
-- several committed steps writes SEVERAL rows instead of updating one:
--   INTENT    approve/reprovision accepted: previous status -> PROVISIONING, committed atomically with the saga claim,
--             BEFORE any schema is created. Exactly one per accepted action.
--   OUTCOME   the saga ended: previous status -> ACTIVE or FAILED. Also the single row of suspend/reactivate.
--   RECLAIM   a stuck PROVISIONING tenant was moved to FAILED (PROVISIONING -> FAILED), same transaction as the move.
--   RECONCILE a retried approve/reprovision found the tenant already ACTIVE and repaired a leftover (signup hash still
--             stored, or an INTENT whose OUTCOME was never written); ACTIVE -> ACTIVE.
-- Rows written before V7 are OUTCOME rows by definition (the default).
ALTER TABLE tenant_audit_log
    ADD COLUMN phase VARCHAR(10) NOT NULL DEFAULT 'OUTCOME';

ALTER TABLE tenant_audit_log
    ADD CONSTRAINT ck_tenant_audit_log_phase CHECK (phase IN ('INTENT', 'OUTCOME', 'RECLAIM', 'RECONCILE'));
