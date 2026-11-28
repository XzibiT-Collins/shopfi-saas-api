-- BILL-01: platform billing settings (ONE versioned, audited row), per-environment Paystack credentials (write-only,
-- encrypted with a versioned key + AAD) and the append-only settings / credentials audit (docs/api/billing.md section 4).
-- Nothing here is a tenant table: billing is MASTER data. The seed is the documented default of every setting.

CREATE TABLE billing_settings
(
    id                                    SMALLINT                 NOT NULL DEFAULT 1,
    version                               INTEGER                  NOT NULL DEFAULT 0,
    updated_at                            TIMESTAMP WITH TIME ZONE,
    updated_by_admin_id                   BIGINT,
    updated_by_email                      VARCHAR(255),
    billing_enabled                       BOOLEAN                  NOT NULL DEFAULT FALSE,
    active_environment                    VARCHAR(4)               NOT NULL DEFAULT 'TEST',
    card_required_at_signup               BOOLEAN                  NOT NULL DEFAULT TRUE,
    grace_days                            INTEGER                  NOT NULL DEFAULT 7,
    retry_offsets_days                    JSONB                    NOT NULL DEFAULT '[1,3,5]'::jsonb,
    retry_time_of_day_utc                 VARCHAR(5),
    trial_reminder_days_before            JSONB                    NOT NULL DEFAULT '[3,1]'::jsonb,
    renewal_reminder_days_before          JSONB                    NOT NULL DEFAULT '[3]'::jsonb,
    price_change_notice_days              INTEGER                  NOT NULL DEFAULT 30,
    grace_reminder_offsets_days           JSONB                    NOT NULL DEFAULT '[1,3,5]'::jsonb,
    grace_ending_reminder_days_before     JSONB                    NOT NULL DEFAULT '[1]'::jsonb,
    email_enabled                         JSONB                    NOT NULL,
    invoice_number_format                 VARCHAR(40)              NOT NULL DEFAULT 'SUB-{YYYY}-{NNNNNN}',
    receipt_number_format                 VARCHAR(40)              NOT NULL DEFAULT 'REC-{YYYY}-{NNNNNN}',
    credit_note_number_format             VARCHAR(40)              NOT NULL DEFAULT 'CN-{YYYY}-{NNNNNN}',
    business_name                         VARCHAR(120),
    business_address                      VARCHAR(300),
    business_tin                          VARCHAR(40),
    business_email                        VARCHAR(254),
    business_phone                        VARCHAR(40),
    tax_label                             VARCHAR(40),
    tax_rate_bps                          INTEGER,
    tax_inclusive                         BOOLEAN                  NOT NULL DEFAULT TRUE,
    invoice_footer_text                   VARCHAR(500),
    attach_pdf_to_email                   BOOLEAN                  NOT NULL DEFAULT FALSE,
    card_verification_amount_pesewas      BIGINT                   NOT NULL DEFAULT 100,
    allowed_channels                      JSONB                    NOT NULL DEFAULT '["card"]'::jsonb,
    recurring_channels                    JSONB                    NOT NULL DEFAULT '["card"]'::jsonb,
    reconcile_interval_minutes            INTEGER                  NOT NULL DEFAULT 15,
    reconcile_lookback_hours              INTEGER                  NOT NULL DEFAULT 48,
    reconcile_full_scan_days              INTEGER                  NOT NULL DEFAULT 30,
    billing_run_interval_seconds          INTEGER                  NOT NULL DEFAULT 60,
    webhook_ip_allowlist_enabled          BOOLEAN                  NOT NULL DEFAULT FALSE,
    webhook_ip_allowlist                  JSONB                    NOT NULL DEFAULT '[]'::jsonb,
    refund_max_days                       INTEGER                  NOT NULL DEFAULT 90,
    refund_confirm_threshold_pesewas      BIGINT                   NOT NULL DEFAULT 100000,
    card_token_retention_days_after_end   INTEGER                  NOT NULL DEFAULT 7,
    one_trial_per_card                    BOOLEAN                  NOT NULL DEFAULT TRUE,
    card_attempts_per_email_per_day       INTEGER                  NOT NULL DEFAULT 5,
    card_decline_alert_threshold_per_hour INTEGER                  NOT NULL DEFAULT 20,
    card_setup_stop_threshold_per_hour    INTEGER                  NOT NULL DEFAULT 100,
    CONSTRAINT pk_billing_settings PRIMARY KEY (id),
    CONSTRAINT ck_billing_settings_single_row CHECK (id = 1),
    CONSTRAINT ck_billing_settings_version CHECK (version >= 0),
    CONSTRAINT ck_billing_settings_env CHECK (active_environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_billing_settings_grace CHECK (grace_days BETWEEN 0 AND 60),
    CONSTRAINT ck_billing_settings_retry CHECK (jsonb_typeof(retry_offsets_days) = 'array' AND jsonb_array_length(retry_offsets_days) <= 6),
    CONSTRAINT ck_billing_settings_retry_time CHECK (retry_time_of_day_utc IS NULL OR retry_time_of_day_utc ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
    CONSTRAINT ck_billing_settings_trial_rem CHECK (jsonb_typeof(trial_reminder_days_before) = 'array' AND jsonb_array_length(trial_reminder_days_before) <= 5),
    CONSTRAINT ck_billing_settings_renewal_rem CHECK (jsonb_typeof(renewal_reminder_days_before) = 'array' AND jsonb_array_length(renewal_reminder_days_before) <= 5),
    CONSTRAINT ck_billing_settings_price_notice CHECK (price_change_notice_days BETWEEN 0 AND 365),
    CONSTRAINT ck_billing_settings_grace_rem CHECK (jsonb_typeof(grace_reminder_offsets_days) = 'array' AND jsonb_array_length(grace_reminder_offsets_days) <= 6),
    CONSTRAINT ck_billing_settings_grace_end_rem CHECK (jsonb_typeof(grace_ending_reminder_days_before) = 'array' AND jsonb_array_length(grace_ending_reminder_days_before) <= 3),
    CONSTRAINT ck_billing_settings_email_enabled CHECK (jsonb_typeof(email_enabled) = 'object'),
    CONSTRAINT ck_billing_settings_invoice_fmt CHECK (char_length(invoice_number_format) BETWEEN 8 AND 40 AND position('{NNNNNN}' IN invoice_number_format) > 0),
    CONSTRAINT ck_billing_settings_receipt_fmt CHECK (char_length(receipt_number_format) BETWEEN 8 AND 40 AND position('{NNNNNN}' IN receipt_number_format) > 0),
    CONSTRAINT ck_billing_settings_credit_fmt CHECK (char_length(credit_note_number_format) BETWEEN 8 AND 40 AND position('{NNNNNN}' IN credit_note_number_format) > 0),
    CONSTRAINT ck_billing_settings_tax_rate CHECK (tax_rate_bps IS NULL OR tax_rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_billing_settings_card_amount CHECK (card_verification_amount_pesewas BETWEEN 100 AND 100000),
    CONSTRAINT ck_billing_settings_channels CHECK (jsonb_typeof(allowed_channels) = 'array' AND jsonb_array_length(allowed_channels) BETWEEN 1 AND 6),
    CONSTRAINT ck_billing_settings_recurring CHECK (jsonb_typeof(recurring_channels) = 'array' AND jsonb_array_length(recurring_channels) BETWEEN 1 AND 6),
    CONSTRAINT ck_billing_settings_recon_interval CHECK (reconcile_interval_minutes BETWEEN 5 AND 1440),
    CONSTRAINT ck_billing_settings_recon_lookback CHECK (reconcile_lookback_hours BETWEEN 1 AND 720),
    CONSTRAINT ck_billing_settings_recon_full CHECK (reconcile_full_scan_days BETWEEN 1 AND 365),
    CONSTRAINT ck_billing_settings_run_interval CHECK (billing_run_interval_seconds BETWEEN 10 AND 3600),
    CONSTRAINT ck_billing_settings_ip_list CHECK (jsonb_typeof(webhook_ip_allowlist) = 'array' AND jsonb_array_length(webhook_ip_allowlist) <= 20),
    CONSTRAINT ck_billing_settings_ip_enabled CHECK (NOT webhook_ip_allowlist_enabled OR jsonb_array_length(webhook_ip_allowlist) >= 1),
    CONSTRAINT ck_billing_settings_refund_days CHECK (refund_max_days BETWEEN 1 AND 365),
    CONSTRAINT ck_billing_settings_refund_confirm CHECK (refund_confirm_threshold_pesewas BETWEEN 0 AND 100000000),
    CONSTRAINT ck_billing_settings_token_days CHECK (card_token_retention_days_after_end BETWEEN 0 AND 365),
    CONSTRAINT ck_billing_settings_card_email_day CHECK (card_attempts_per_email_per_day BETWEEN 1 AND 50),
    CONSTRAINT ck_billing_settings_card_alert CHECK (card_decline_alert_threshold_per_hour BETWEEN 1 AND 10000),
    CONSTRAINT ck_billing_settings_card_stop CHECK (card_setup_stop_threshold_per_hour BETWEEN 1 AND 100000
        AND card_setup_stop_threshold_per_hour >= card_decline_alert_threshold_per_hour)
);

-- The documented defaults (billing.md 4.1). Identity fields stay NULL until the owner supplies them (D4).
INSERT INTO billing_settings (id, email_enabled)
VALUES (1, '{"TRIAL_REMINDER": true, "TRIAL_ENDS_TODAY": true, "TRIAL_ENDED": true, "PAYMENT_SUCCEEDED": true,
  "PAYMENT_FAILED": true, "RETRY_SCHEDULED": true, "GRACE_STARTED": true, "GRACE_REMINDER": true, "GRACE_ENDING": true,
  "ACCESS_RESTRICTED": true, "ACCESS_RESTORED": true, "RENEWAL_REMINDER": true, "UPGRADE_APPLIED": true,
  "DOWNGRADE_SCHEDULED": true, "DOWNGRADE_APPLIED": true, "CANCELLATION_SCHEDULED": true, "CANCELLATION_REVERSED": true,
  "SUBSCRIPTION_ENDED": true, "INVOICE_AVAILABLE": true, "REFUND_ISSUED": true, "PRICE_CHANGE_NOTICE": true,
  "COMPLIMENTARY_STARTED": true, "COMPLIMENTARY_ENDING": true, "COMPLIMENTARY_ENDED": true, "CARD_EXPIRING": true,
  "CARD_UPDATED": true, "TRIAL_NOT_GRANTED": true}'::jsonb);

-- One row per environment. The secret key is AES-GCM ciphertext "v<keyVersion>:<base64>" with AAD = row and field
-- (BillingSecretCipher); it is never returned by any API. public_key is not a secret but only its last 4 are returned.
CREATE TABLE billing_paystack_credentials
(
    environment          VARCHAR(4)               NOT NULL,
    public_key           VARCHAR(160),
    public_last4         VARCHAR(4),
    secret_key_encrypted TEXT,
    secret_key_version   SMALLINT,
    secret_last4         VARCHAR(4),
    status               VARCHAR(10)              NOT NULL DEFAULT 'UNSET',
    status_changed_at    TIMESTAMP WITH TIME ZONE,
    verified_at          TIMESTAMP WITH TIME ZONE,
    failure_code         VARCHAR(20),
    last_webhook_at      TIMESTAMP WITH TIME ZONE,
    last_webhook_ok_at   TIMESTAMP WITH TIME ZONE,
    version              INTEGER                  NOT NULL DEFAULT 0,
    updated_at           TIMESTAMP WITH TIME ZONE,
    updated_by_admin_id  BIGINT,
    updated_by_email     VARCHAR(255),
    CONSTRAINT pk_billing_paystack_credentials PRIMARY KEY (environment),
    CONSTRAINT ck_billing_credentials_env CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_billing_credentials_status CHECK (status IN ('UNSET', 'UNVERIFIED', 'VERIFIED', 'FAILED')),
    CONSTRAINT ck_billing_credentials_failure CHECK (failure_code IS NULL OR failure_code = 'KEY_REJECTED'),
    CONSTRAINT ck_billing_credentials_unset CHECK ((status = 'UNSET') = (secret_key_encrypted IS NULL)),
    CONSTRAINT ck_billing_credentials_secret_has_key CHECK (secret_key_encrypted IS NULL
        OR (secret_key_version IS NOT NULL AND public_key IS NOT NULL AND secret_last4 IS NOT NULL)),
    -- a key of the other environment can never be stored (the API checks this first; this is the second line)
    CONSTRAINT ck_billing_credentials_pk_prefix CHECK (public_key IS NULL
        OR public_key LIKE (CASE environment WHEN 'TEST' THEN 'pk\_test\_%' ELSE 'pk\_live\_%' END)),
    CONSTRAINT ck_billing_credentials_version CHECK (version >= 0)
);

INSERT INTO billing_paystack_credentials (environment)
VALUES ('TEST'),
       ('LIVE');

-- Append-only audit of every settings save and every credentials write / verify (billing.md 4.6, S2, S6): one row per
-- change with field-level before / after; credential fields are "unset" / "changed" / last 4 only, never a key. No
-- foreign keys on purpose (history must outlive what it describes), like plan_audit_log.
CREATE TABLE billing_settings_audit_log
(
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    occurred_at      TIMESTAMP WITH TIME ZONE                NOT NULL DEFAULT now(),
    actor_admin_id   BIGINT                                  NOT NULL,
    actor_email      VARCHAR(255)                            NOT NULL,
    settings_version INTEGER                                 NOT NULL,
    changes          JSONB                                   NOT NULL,
    CONSTRAINT pk_billing_settings_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_billing_settings_audit_changes CHECK (jsonb_typeof(changes) = 'array' AND jsonb_array_length(changes) >= 1)
);

CREATE INDEX idx_billing_settings_audit_log_occurred ON billing_settings_audit_log (id DESC);

CREATE FUNCTION billing_settings_audit_log_immutable() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'billing_settings_audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_billing_settings_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON billing_settings_audit_log
    FOR EACH ROW EXECUTE FUNCTION billing_settings_audit_log_immutable();

CREATE TRIGGER trg_billing_settings_audit_log_no_truncate
    BEFORE TRUNCATE ON billing_settings_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION billing_settings_audit_log_immutable();
