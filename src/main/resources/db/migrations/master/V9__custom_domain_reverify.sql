-- DOMAIN-03: scheduled re-verification of VERIFIED custom domains.
--
-- Per-row schedule/state, meaningful ONLY while custom_domain_status = 'VERIFIED' (the check constraint below keeps
-- every other row clean, so no code path can leave stale counters behind a PENDING/FAILED/removed domain):
--   custom_domain_reverify_due_at       next time the job may re-check this domain. The job also CLAIMS a row by pushing
--                                       this forward by a lease (UPDATE ... FOR UPDATE SKIP LOCKED), so two instances
--                                       never check the same row at once and a crashed instance's rows come back
--                                       when the lease ends. NULL on a VERIFIED row = due now (legacy/fixture rows).
--   custom_domain_miss_count            consecutive DEFINITE "TXT proof absent" answers (DNS errors do not count).
--   custom_domain_missing_since         time of the first miss of the current streak (grace period start).
ALTER TABLE tenants
    ADD COLUMN custom_domain_reverify_due_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN custom_domain_miss_count      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN custom_domain_missing_since   TIMESTAMP WITH TIME ZONE;

-- Spread the first checks of already VERIFIED domains over the first day (no thundering herd on the first start).
UPDATE tenants
SET custom_domain_reverify_due_at = now() + random() * interval '24 hours'
WHERE custom_domain_status = 'VERIFIED';

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_custom_domain_reverify_state
        CHECK (custom_domain_miss_count >= 0
            AND (custom_domain_status = 'VERIFIED'
                OR (custom_domain_reverify_due_at IS NULL AND custom_domain_miss_count = 0
                    AND custom_domain_missing_since IS NULL)));

CREATE INDEX idx_tenants_custom_domain_reverify_due ON tenants (custom_domain_reverify_due_at)
    WHERE custom_domain_status = 'VERIFIED';

-- New audit action: REVERIFY = the scheduled job (actor SYSTEM) turned VERIFIED into FAILED.
ALTER TABLE tenant_domain_audit_log DROP CONSTRAINT ck_tenant_domain_audit_action;
ALTER TABLE tenant_domain_audit_log
    ADD CONSTRAINT ck_tenant_domain_audit_action CHECK (action IN ('REQUEST', 'VERIFY', 'REMOVE', 'RELEASE', 'REVERIFY'));
