-- ACCT-AUTH-BE (API-RD-08): server-side revocation of customer/staff sessions. Every access and refresh token carries
-- the user's token_version in the `tv` claim (a token without the claim counts as version 0); a password change bumps
-- the version, so every token minted before it (any device) stops authenticating while the session that made the
-- change is re-issued at the new version. Additive and re-runnable (IF NOT EXISTS) like V17/V18.
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
