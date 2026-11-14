-- PROV-02b: OTP resend bookkeeping (bounded resends + cooldown) on the pending-signup row.
ALTER TABLE tenant_signups
    ADD COLUMN resend_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_otp_sent_at TIMESTAMP;
